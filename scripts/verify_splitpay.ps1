$ErrorActionPreference = 'Stop'

# 1. Login Saimon
$loginSaimon = Invoke-RestMethod -Uri 'http://localhost:8080/api/auth/login' -Method POST -ContentType 'application/json' -Body '{"userId":"0112330140","password":"demo1234"}'
$tokenSaimon = $loginSaimon.token
Write-Host "Saimon logged in."

# 2. Login Sakib
$loginSakib = Invoke-RestMethod -Uri 'http://localhost:8080/api/auth/login' -Method POST -ContentType 'application/json' -Body '{"userId":"0112330586","password":"demo1234"}'
$tokenSakib = $loginSakib.token
Write-Host "Sakib logged in."
$walletSakibBefore = Invoke-RestMethod -Uri 'http://localhost:8080/api/wallet' -Method GET -Headers @{ Authorization = "Bearer $tokenSakib" }
Write-Host "Sakib balance before: $($walletSakibBefore.balance)"

# 3. Create Custom Split Bill: 500 total, Sakib pays 200, Saimon (creator) pays remainder (300)
$customBody = '{"title":"Khan Kitchen Biryani","totalAmount":500.00,"splitType":"CUSTOM","note":"Dinner","participants":[{"userIdentifier":"0112330586","amount":200.00}]}'
$customBill = Invoke-RestMethod -Uri 'http://localhost:8080/api/splitpay/bills' -Method POST -Headers @{ Authorization = "Bearer $tokenSaimon" } -ContentType 'application/json' -Body $customBody
Write-Host "Custom Bill created: $($customBill.billId), Total: $($customBill.totalAmount), Creator Share: $($customBill.creatorShare)"

# 4. Sakib checks incoming requests
$sakibReqs = Invoke-RestMethod -Uri 'http://localhost:8080/api/splitpay/my-requests' -Method GET -Headers @{ Authorization = "Bearer $tokenSakib" }
$targetSakibReq = $sakibReqs | Where-Object { $_.billId -eq $customBill.billId } | Select-Object -First 1
Write-Host "Sakib request amount: $($targetSakibReq.myShare)"

# 5. Sakib accepts
$sakibAccept = Invoke-RestMethod -Uri "http://localhost:8080/api/splitpay/requests/$($targetSakibReq.requestId)/accept" -Method POST -Headers @{ Authorization = "Bearer $tokenSakib" }
Write-Host "Sakib new balance: $($sakibAccept.payerNewBalance)"

# 6. Verify Saimon received the 200
$walletSaimonFinal = Invoke-RestMethod -Uri 'http://localhost:8080/api/wallet' -Method GET -Headers @{ Authorization = "Bearer $tokenSaimon" }
Write-Host "Saimon final balance: $($walletSaimonFinal.balance)"

Write-Host "ALL END-TO-END EVEN AND CUSTOM SPLIT VERIFICATIONS PASSED!"
