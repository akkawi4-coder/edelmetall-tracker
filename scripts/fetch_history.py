#!/usr/bin/env python3
"""
Lädt beim Bauen der App die Kurshistorie und schreibt sie nach app/src/main/assets/history.json.

- Metallpreise: gold-api.com /history (kostenloser API-Schlüssel nötig, max. 10 Abrufe pro Stunde)
  Der Schlüssel liegt nur als GitHub-Secret GOLDAPI_KEY vor – niemals in der App.
- Umrechnung USD -> EUR: Referenzkurse der EZB über frankfurter (frei, ohne Schlüssel)

Zeitraum: ab Euro-Einführung (04.01.1999) monatlich, die letzten 5 Jahre täglich.
Ohne Schlüssel oder bei Fehlern bleibt die vorhandene Datei unverändert und der Build läuft trotzdem.
"""
import bisect
import json
import os
import sys
import time
import urllib.parse
import urllib.request
from datetime import date, datetime, timedelta, timezone

OUT = sys.argv[1] if len(sys.argv) > 1 else "app/src/main/assets/history.json"
KEY = os.environ.get("GOLDAPI_KEY", "").strip()
SYMBOLS = ["XAU", "XAG", "XPT", "XPD"]
EURO_START = date(1999, 1, 4)
UA = "EdelmetallTracker-Build/0.1"


def get_json(url, headers=None):
    h = {"User-Agent": UA, "Accept": "application/json"}
    h.update(headers or {})
    req = urllib.request.Request(url, headers=h)
    with urllib.request.urlopen(req, timeout=90) as r:
        return json.loads(r.read().decode("utf-8"))


def ts(d):
    return int(datetime(d.year, d.month, d.day, tzinfo=timezone.utc).timestamp())


def parse_period(v):
    """Akzeptiert Unix-Sekunden/-Millisekunden, 'YYYY-MM-DD...', 'YYYY-MM' oder 'YYYY'."""
    if v is None:
        return None
    if isinstance(v, (int, float)):
        x = float(v)
        if x > 1e11:
            x /= 1000.0
        return datetime.fromtimestamp(x, tz=timezone.utc).date()
    s = str(v).strip()
    try:
        return date.fromisoformat(s[:10])
    except ValueError:
        pass
    try:
        if len(s) >= 7 and s[4] == "-":
            return date(int(s[:4]), int(s[5:7]), 1)
        if len(s) == 4:
            return date(int(s), 1, 1)
    except ValueError:
        pass
    try:
        return parse_period(float(s))
    except ValueError:
        return None


def rows_of(data):
    if isinstance(data, list):
        return data
    if isinstance(data, dict):
        for k in ("data", "history", "results", "prices", "items"):
            if isinstance(data.get(k), list):
                return data[k]
    return []


def history(symbol, start, end, group):
    q = urllib.parse.urlencode({
        "symbol": symbol,
        "startTimestamp": ts(start),
        "endTimestamp": ts(end),
        "groupBy": group,
        "aggregation": "avg",
        "orderBy": "asc",
    })
    data = get_json("https://api.gold-api.com/history?" + q, {"x-api-key": KEY})
    out = {}
    for r in rows_of(data):
        if not isinstance(r, dict):
            continue
        period = r.get(group, r.get("date", r.get("timestamp")))
        price = r.get("avg_price", r.get("price", r.get("max_price")))
        d = parse_period(period)
        try:
            p = float(price)
        except (TypeError, ValueError):
            continue
        if d and p > 0:
            out[d] = p
    return out


def usd_to_eur_rates(start, end):
    rates = {}
    for year in range(start.year, end.year + 1):
        a = max(start, date(year, 1, 1))
        b = min(end, date(year, 12, 31))
        data = None
        for base in ("https://api.frankfurter.dev/v1", "https://api.frankfurter.app"):
            try:
                data = get_json(f"{base}/{a.isoformat()}..{b.isoformat()}?from=USD&to=EUR")
                break
            except Exception as e:  # noqa: BLE001
                print(f"  Wechselkurs {year} über {base} fehlgeschlagen: {e}")
        if data:
            for k, v in data.get("rates", {}).items():
                try:
                    rates[date.fromisoformat(k)] = float(v["EUR"])
                except (KeyError, ValueError, TypeError):
                    pass
        time.sleep(0.3)
    return rates


def main():
    if not KEY:
        print("Kein GOLDAPI_KEY gesetzt – Kurshistorie wird nicht eingebunden (App nutzt nur eigene Aufzeichnung).")
        return 0

    today = datetime.now(timezone.utc).date()
    five_years_ago = today - timedelta(days=5 * 365 + 1)

    print("Lade EUR-Wechselkurse …")
    rates = usd_to_eur_rates(EURO_START, today)
    if not rates:
        print("Keine Wechselkurse erhalten – breche ab, vorhandene Datei bleibt.")
        return 0
    rate_days = sorted(rates)

    def rate_on(d):
        i = bisect.bisect_right(rate_days, d) - 1
        return rates[rate_days[i]] if i >= 0 else rates[rate_days[0]]

    result = {}
    for sym in SYMBOLS:
        points = {}
        for (start, end, group) in ((EURO_START, five_years_ago, "month"), (five_years_ago, today + timedelta(days=1), "day")):
            try:
                part = history(sym, start, end, group)
                print(f"  {sym} {group}: {len(part)} Werte")
                points.update(part)
            except Exception as e:  # noqa: BLE001
                print(f"  {sym} {group} fehlgeschlagen: {e}")
            time.sleep(3)
        epoch = date(1970, 1, 1)
        result[sym] = [[(d - epoch).days, round(p * rate_on(d), 4)] for d, p in sorted(points.items())]

    if not any(result.values()):
        print("Keine Kursdaten erhalten – vorhandene Datei bleibt unverändert.")
        return 0

    os.makedirs(os.path.dirname(OUT) or ".", exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump({"generated": today.isoformat(), "currency": "EUR", "unit": "oz", "metals": result}, f, separators=(",", ":"))
    print(f"Geschrieben: {OUT} ({sum(len(v) for v in result.values())} Werte)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
