package bd.edu.uiu.unipay.splitpay;

import bd.edu.uiu.unipay.audit.AuditService;
import bd.edu.uiu.unipay.common.ApiException;
import bd.edu.uiu.unipay.common.TxCallbacks;
import bd.edu.uiu.unipay.notification.NotificationService;
import bd.edu.uiu.unipay.transaction.Transaction;
import bd.edu.uiu.unipay.transaction.TransactionRepository;
import bd.edu.uiu.unipay.transaction.TransactionType;
import bd.edu.uiu.unipay.user.User;
import bd.edu.uiu.unipay.user.UserRepository;
import bd.edu.uiu.unipay.wallet.Wallet;
import bd.edu.uiu.unipay.wallet.WalletRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Service orchestrating bill splitting (SplitPay), money requests among peers,
 * and automated wallet debits/credits on acceptance with deadlock-free row locking.
 */
@Service
public class SplitPayService {

    private static final Logger log = LoggerFactory.getLogger(SplitPayService.class);

    private final SplitBillRepository billRepository;
    private final SplitRequestRepository requestRepository;
    private final UserRepository userRepository;
    private final WalletRepository walletRepository;
    private final TransactionRepository transactionRepository;
    private final NotificationService notificationService;
    private final AuditService auditService;

    public SplitPayService(SplitBillRepository billRepository,
                           SplitRequestRepository requestRepository,
                           UserRepository userRepository,
                           WalletRepository walletRepository,
                           TransactionRepository transactionRepository,
                           NotificationService notificationService,
                           AuditService auditService) {
        this.billRepository = billRepository;
        this.requestRepository = requestRepository;
        this.userRepository = userRepository;
        this.walletRepository = walletRepository;
        this.transactionRepository = transactionRepository;
        this.notificationService = notificationService;
        this.auditService = auditService;
    }

    /**
     * Creates a new SplitPay bill and generates money requests for each participant.
     */
    @Transactional
    public SplitDtos.BillResponse createBill(String creatorUserId, SplitDtos.CreateBillRequest request) {
        User creator = userRepository.findById(creatorUserId)
                .orElseThrow(() -> ApiException.unauthorized("Authenticated user not found."));

        if (request.totalAmount() == null || request.totalAmount().compareTo(new BigDecimal("1.00")) < 0) {
            throw ApiException.badRequest("Total bill amount must be at least ৳1.00.");
        }

        if (request.participants() == null || request.participants().isEmpty()) {
            throw ApiException.badRequest("At least one friend must be selected to split the bill.");
        }

        // Validate participants (cannot include creator or duplicates)
        Set<String> seenUserIds = new HashSet<>();
        List<ResolvedParticipant> resolved = new ArrayList<>();

        for (SplitDtos.ParticipantItem item : request.participants()) {
            String key = item.userIdentifier() == null ? "" : item.userIdentifier().trim();
            if (key.isBlank()) continue;

            User user = userRepository.findById(key)
                    .or(() -> userRepository.findByPhoneNumber(key))
                    .orElseThrow(() -> ApiException.notFound("No registered UniPay user found for ID or phone: " + key));

            if (user.getUserId().equals(creator.getUserId())) {
                throw ApiException.badRequest("You cannot add yourself as a split participant.");
            }

            if (!seenUserIds.add(user.getUserId())) {
                throw ApiException.badRequest("Duplicate participant in split: " + user.getFullName() + " (" + user.getUserId() + ")");
            }

            resolved.add(new ResolvedParticipant(user, item.amount()));
        }

        if (resolved.isEmpty()) {
            throw ApiException.badRequest("At least one valid participant is required.");
        }

        String billId = "SPLIT-" + UUID.randomUUID().toString().substring(0, 12);
        SplitBill bill = new SplitBill(billId, creator, request.title().trim(), request.totalAmount(), request.splitType(),
                request.note() == null ? "" : request.note().trim());

        List<SplitRequest> requests = new ArrayList<>();
        int totalPeople = resolved.size() + 1; // creator + participants

        if (request.splitType() == SplitType.EVEN) {
            BigDecimal evenShare = request.totalAmount()
                    .divide(BigDecimal.valueOf(totalPeople), 2, RoundingMode.HALF_UP);

            for (ResolvedParticipant p : resolved) {
                String reqId = "REQ-" + UUID.randomUUID().toString().substring(0, 12);
                SplitRequest req = new SplitRequest(reqId, bill, p.user, evenShare);
                bill.addRequest(req);
                requests.add(req);
            }
        } else {
            // CUSTOM split
            BigDecimal sumParticipants = BigDecimal.ZERO;
            for (ResolvedParticipant p : resolved) {
                if (p.customAmount == null || p.customAmount.compareTo(new BigDecimal("0.50")) < 0) {
                    throw ApiException.badRequest("Custom contribution for " + p.user.getFullName() + " must be at least ৳0.50.");
                }
                sumParticipants = sumParticipants.add(p.customAmount);
            }

            if (sumParticipants.compareTo(request.totalAmount()) > 0) {
                throw ApiException.badRequest("Total participant contributions (৳%s) exceed the bill amount (৳%s)."
                        .formatted(sumParticipants.toPlainString(), request.totalAmount().toPlainString()));
            }

            for (ResolvedParticipant p : resolved) {
                String reqId = "REQ-" + UUID.randomUUID().toString().substring(0, 12);
                SplitRequest req = new SplitRequest(reqId, bill, p.user, p.customAmount);
                bill.addRequest(req);
                requests.add(req);
            }
        }

        SplitBill savedBill = billRepository.save(bill);

        // Async dispatch WebSocket notifications to each requested participant
        TxCallbacks.afterCommit(() -> {
            for (SplitRequest r : requests) {
                notificationService.pushSplitRequest(
                        r.getParticipant(),
                        creator,
                        savedBill.getTitle(),
                        r.getAmount(),
                        r.getRequestId()
                );
            }
        });

        return mapBillToResponse(savedBill);
    }

    /**
     * Accepts a SplitPay request, deducting the share from participant's wallet
     * and crediting it to the creator's wallet with deadlock-free row locking.
     */
    @Transactional
    public SplitDtos.AcceptResponse acceptRequest(String participantUserId, String requestId) {
        SplitRequest request = requestRepository.findByIdWithDetails(requestId)
                .orElseThrow(() -> ApiException.notFound("Split request not found: " + requestId));

        if (!request.getParticipant().getUserId().equals(participantUserId)) {
            throw ApiException.forbidden("You are not authorized to accept this split request.");
        }

        if (request.getStatus() != SplitRequestStatus.PENDING) {
            throw ApiException.conflict("This request is already " + request.getStatus() + ".");
        }

        SplitBill bill = request.getBill();
        if (bill.getStatus() != SplitBillStatus.ACTIVE) {
            throw ApiException.conflict("This bill is no longer active (status: " + bill.getStatus() + ").");
        }

        User participant = request.getParticipant();
        User creator = bill.getCreator();

        // Concurrency guarantee: lock wallets in ascending user-ID order
        Map<String, Wallet> locked = lockBothWallets(participant.getUserId(), creator.getUserId());
        Wallet participantWallet = locked.get(participant.getUserId());
        Wallet creatorWallet = locked.get(creator.getUserId());

        if (!participantWallet.hasAtLeast(request.getAmount())) {
            throw ApiException.conflict("Insufficient balance to pay split share. Required: ৳%s, Available: ৳%s."
                    .formatted(request.getAmount().toPlainString(), participantWallet.getCurrentBalance().toPlainString()));
        }

        // Transfer funds
        participantWallet.debit(request.getAmount());
        creatorWallet.credit(request.getAmount());

        String txnId = "TXN-SPLIT-" + requestId;
        Transaction txn = persistLedger(txnId, participant, creator, request.getAmount(), TransactionType.SPLIT_PAY);

        request.setStatus(SplitRequestStatus.ACCEPTED);
        request.setTransactionId(txn.getTransactionId());
        request.setPaidAt(Instant.now());
        requestRepository.save(request);

        // Check if all requests for this bill are now settled
        List<SplitRequest> allRequests = requestRepository.findByBill_BillId(bill.getBillId());
        boolean allSettled = allRequests.stream().allMatch(r -> r.getStatus() == SplitRequestStatus.ACCEPTED);
        if (allSettled) {
            bill.setStatus(SplitBillStatus.SETTLED);
            billRepository.save(bill);
        }

        // Async post-commit notifications and audit trail
        TxCallbacks.afterCommit(() -> {
            notificationService.pushSplitAccepted(creator, participant, bill.getTitle(), request.getAmount(), txn.getTransactionId());
            if (allSettled) {
                notificationService.pushSplitSettled(creator, bill.getTitle(), bill.getTotalAmount());
            }
            auditService.logLedgerEntry(txn, participant.getUserId());
        });

        return new SplitDtos.AcceptResponse(
                request.getRequestId(),
                txn.getTransactionId(),
                request.getAmount(),
                participantWallet.getCurrentBalance(),
                "Successfully paid ৳%s for '%s' to %s."
                        .formatted(request.getAmount().toPlainString(), bill.getTitle(), creator.getFullName())
        );
    }

    /**
     * Declines a SplitPay request.
     */
    @Transactional
    public void declineRequest(String participantUserId, String requestId) {
        SplitRequest request = requestRepository.findByIdWithDetails(requestId)
                .orElseThrow(() -> ApiException.notFound("Split request not found: " + requestId));

        if (!request.getParticipant().getUserId().equals(participantUserId)) {
            throw ApiException.forbidden("You are not authorized to decline this split request.");
        }

        if (request.getStatus() != SplitRequestStatus.PENDING) {
            throw ApiException.conflict("This request is already " + request.getStatus() + ".");
        }

        request.setStatus(SplitRequestStatus.DECLINED);
        requestRepository.save(request);

        SplitBill bill = request.getBill();
        User creator = bill.getCreator();
        User participant = request.getParticipant();

        TxCallbacks.afterCommit(() -> {
            notificationService.pushSplitDeclined(creator, participant, bill.getTitle());
        });
    }

    /**
     * Cancels an active SplitBill created by the user.
     */
    @Transactional
    public void cancelBill(String creatorUserId, String billId) {
        SplitBill bill = billRepository.findByIdWithRequests(billId)
                .orElseThrow(() -> ApiException.notFound("Split bill not found: " + billId));

        if (!bill.getCreator().getUserId().equals(creatorUserId)) {
            throw ApiException.forbidden("You can only cancel bills created by you.");
        }

        if (bill.getStatus() != SplitBillStatus.ACTIVE) {
            throw ApiException.conflict("Cannot cancel bill with status " + bill.getStatus());
        }

        bill.setStatus(SplitBillStatus.CANCELLED);
        for (SplitRequest req : bill.getRequests()) {
            if (req.getStatus() == SplitRequestStatus.PENDING) {
                req.setStatus(SplitRequestStatus.CANCELLED);
            }
        }
        billRepository.save(bill);
    }

    /**
     * Gets all bills created by the user.
     */
    @Transactional(readOnly = true)
    public List<SplitDtos.BillResponse> getMyBills(String creatorUserId) {
        List<SplitBill> bills = billRepository.findByCreatorWithRequests(creatorUserId);
        return bills.stream().map(this::mapBillToResponse).toList();
    }

    /**
     * Gets all incoming requests addressed to the participant.
     */
    @Transactional(readOnly = true)
    public List<SplitDtos.IncomingRequestResponse> getMyRequests(String participantUserId) {
        List<SplitRequest> requests = requestRepository.findByParticipantWithDetails(participantUserId);
        return requests.stream().map(r -> new SplitDtos.IncomingRequestResponse(
                r.getRequestId(),
                r.getBill().getBillId(),
                r.getBill().getTitle(),
                r.getBill().getTotalAmount(),
                r.getBill().getSplitType(),
                r.getBill().getCreator().getUserId(),
                r.getBill().getCreator().getFullName(),
                r.getAmount(),
                r.getStatus(),
                r.getBill().getNote(),
                r.getTransactionId(),
                r.getPaidAt() == null ? null : r.getPaidAt().toString(),
                r.getCreatedAt() == null ? null : r.getCreatedAt().toString()
        )).toList();
    }

    /**
     * Count of pending requests for the authenticated user (for badge indicators).
     */
    @Transactional(readOnly = true)
    public long getPendingCount(String userId) {
        return requestRepository.countByParticipant_UserIdAndStatus(userId, SplitRequestStatus.PENDING);
    }

    // ------------------------------------------------------------- helper methods

    private Map<String, Wallet> lockBothWallets(String userIdA, String userIdB) {
        String first = userIdA.compareTo(userIdB) <= 0 ? userIdA : userIdB;
        String second = first.equals(userIdA) ? userIdB : userIdA;

        Wallet firstWallet = walletRepository.findWalletForUpdateByUserId(first)
                .orElseThrow(() -> ApiException.notFound("Wallet not found for user " + first));
        Wallet secondWallet = walletRepository.findWalletForUpdateByUserId(second)
                .orElseThrow(() -> ApiException.notFound("Wallet not found for user " + second));

        Map<String, Wallet> byUserId = new HashMap<>();
        byUserId.put(firstWallet.getUser().getUserId(), firstWallet);
        byUserId.put(secondWallet.getUser().getUserId(), secondWallet);
        return byUserId;
    }

    private Transaction persistLedger(String txnId, User sender, User receiver,
                                      BigDecimal amount, TransactionType type) {
        try {
            return transactionRepository.save(new Transaction(txnId, sender, receiver, amount, type));
        } catch (DataIntegrityViolationException race) {
            return transactionRepository.findById(txnId)
                    .orElseThrow(() -> race);
        }
    }

    private SplitDtos.BillResponse mapBillToResponse(SplitBill bill) {
        BigDecimal collected = BigDecimal.ZERO;
        BigDecimal participantTotal = BigDecimal.ZERO;
        List<SplitDtos.ParticipantDetail> details = new ArrayList<>();

        for (SplitRequest r : bill.getRequests()) {
            participantTotal = participantTotal.add(r.getAmount());
            if (r.getStatus() == SplitRequestStatus.ACCEPTED) {
                collected = collected.add(r.getAmount());
            }
            details.add(new SplitDtos.ParticipantDetail(
                    r.getRequestId(),
                    r.getParticipant().getUserId(),
                    r.getParticipant().getFullName(),
                    r.getAmount(),
                    r.getStatus(),
                    r.getTransactionId(),
                    r.getPaidAt() == null ? null : r.getPaidAt().toString(),
                    r.getCreatedAt() == null ? null : r.getCreatedAt().toString()
            ));
        }

        BigDecimal creatorShare = bill.getTotalAmount().subtract(participantTotal);
        if (creatorShare.compareTo(BigDecimal.ZERO) < 0) creatorShare = BigDecimal.ZERO;

        BigDecimal remaining = bill.getTotalAmount().subtract(creatorShare).subtract(collected);
        if (remaining.compareTo(BigDecimal.ZERO) < 0) remaining = BigDecimal.ZERO;

        return new SplitDtos.BillResponse(
                bill.getBillId(),
                bill.getTitle(),
                bill.getTotalAmount(),
                bill.getSplitType(),
                bill.getStatus(),
                bill.getNote(),
                bill.getCreator().getUserId(),
                bill.getCreator().getFullName(),
                creatorShare,
                collected,
                remaining,
                bill.getCreatedAt() == null ? Instant.now().toString() : bill.getCreatedAt().toString(),
                details
        );
    }

    private record ResolvedParticipant(User user, BigDecimal customAmount) {
    }
}
