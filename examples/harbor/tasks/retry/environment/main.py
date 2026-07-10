def retry_delays(attempts, base=1, cap=30):
    return [base for _ in range(attempts)]
