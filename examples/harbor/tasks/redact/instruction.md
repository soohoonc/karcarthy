Fix `/app/main.py`. `redact(value)` must recursively traverse dictionaries and
lists and replace values for `password`, `token`, `api_key`, and
`authorization` keys with `"***"`. Key matching must be case-insensitive.
Preserve non-sensitive values and the surrounding data structure.
