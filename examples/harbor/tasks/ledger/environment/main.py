def balances(transactions):
    return {
        item["currency"]: item["amount_cents"]
        for item in transactions
        if item["status"] == "settled"
    }
