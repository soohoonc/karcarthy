SENSITIVE_KEYS = {"password"}


def redact(value):
    if not isinstance(value, dict):
        return value
    return {
        key: "***" if key in SENSITIVE_KEYS else item
        for key, item in value.items()
    }
