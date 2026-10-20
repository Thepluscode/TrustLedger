"""CSV row hashes and exact monetary cells shared by independent provider-report readers."""
import csv
import hashlib
import io
import re
from decimal import Decimal


DECIMAL = re.compile(r"[+-]?\d{1,15}(\.\d{1,8})?\Z")


def row_sha(source, fields):
    out = io.StringIO()
    csv.writer(out, lineterminator="").writerow(fields)
    raw = out.getvalue()
    joined = "".join(x + "\x1f" for x in (source["sourceType"], source["sourceIdentity"], raw))
    return hashlib.sha256(joined.encode()).hexdigest()


def monetary_cell(raw, currency, field, unsigned=False):
    if not raw:
        return None
    if not DECIMAL.fullmatch(raw) or (unsigned and raw[0] in "+-"):
        raise ValueError("invalid provider amount in %s" % field)
    if not re.fullmatch(r"[A-Z]{3}", currency.upper()):
        raise ValueError("amount without a usable currency in %s" % field)
    return currency.upper(), Decimal(raw)
