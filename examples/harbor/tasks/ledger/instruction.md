Fix `/app/main.py`. `balances(transactions)` must total `amount_cents` for
settled transactions by currency, normalize currency codes to uppercase,
accumulate repeated currencies, ignore transactions with other statuses, and
preserve negative settled amounts such as refunds. Preserve the public
function signature.
