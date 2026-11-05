#!/usr/bin/env python3
"""
Lädt beim Bauen der App die Kurshistorie und schreibt sie nach app/src/main/assets/history.json.
Läuft auf GitHub (Actions) – die App selbst braucht dafür keinen Server und keinen Schlüssel.

Quellen (alle frei, ohne Anmeldung), es wird die erste genommen, die Daten liefert:
  1. gold-api.com /history   – nur falls das Secret GOLDAPI_KEY gesetzt ist
  2. Stooq                   – Spotkurse XAUUSD, XAGUSD, XPTUSD, XPDUSD (Tageswerte)
  3. Yahoo Finance           – Terminkontrakte GC=F, SI=F, PL=F, PA=F (Tageswerte ab 2000)
  Lücken vor dem Beginn einer Tagesreihe werden mit Monatswerten (datasets/gold-prices, nur Gold) gefüllt.
Umrechnung USD -> EUR: Referenzkurse der EZB (ab 04.01.1999).

Ergebnis: ab Euro-Einführung, die letzten 5 Jahre täglich, davor wöchentlich (hält die APK klein).
Ohne Daten bleibt die vorhandene Datei unverändert und der Build läuft trotzdem.
Ein kurzes Protokoll landet in data/history-log.txt.
"""
import bisect
import csv
import io
import json
import os
import sys
import time
import urllib.parse
import urllib.request
from datetime import date, datetime, timedelta, timezone

OUT = sys.argv[1] if len(sys.argv) > 1 else "app/src/main/assets/history.json"
LOG = sys.argv[2] if len(sys.argv) > 2 else "data/history-log.txt"
KEY = os.environ.get("GOLDAPI_KEY", "").strip()
SYMBOLS = ["XAU", "XAG", "XPT", "XPD"]
YAHOO = {"XAU": "GC=F", "XAG": "SI=F", "XPT": "PL=F", "XPD": "PA=F"}
EURO_START = date(1999, 1, 4)
UA = "Mozilla/5.0 (X11; Linux x86_64) EdelmetallTracker-Build/0.2"
EPOCH = date(1970, 1, 1)

log_lines = []


def log(msg):
    print(msg)
    log_lines.append(msg)


def http(url, headers=None, timeout=90):
    h = {"User-Agent": UA, "Accept": "*/*"}
    h.update(headers or {})
    req = urllib.request.Request(url, headers=h)
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read().decode("utf-8", errors="replace")


def ts(d):
    return int(datetime(d.year, d.month, d.day, tzinfo=timezone.utc).timestamp())


# ------------------------------------------------------------------ Wechselkurse

def ecb_usd_per_eur():
    url = ("https://data-api.ecb.europa.eu/service/data/EXR/D.USD.EUR.SP00.A"
           "?format=csvdata&startPeriod=1999-01-01")
    rates = {}
    text = http(url, {"Accept": "text/csv"})
    for row in csv.DictReader(io.StringIO(text)):
        try:
            rates[date.fromisoformat(row["TIME_PERIOD"])] = float(row["OBS_VALUE"])
        except (KeyError, ValueError, TypeError):
            pass
    return rates


def frankfurter_usd_per_eur(start, end):
    rates = {}
    for year in range(start.year, end.year + 1):
        a = max(start, date(year, 1, 1))
        b = min(end, date(year, 12, 31))
        for base in ("https://api.frankfurter.dev/v1", "https://api.frankfurter.app"):
            try:
                data = json.loads(http(f"{base}/{a.isoformat()}..{b.isoformat()}?from=EUR&to=USD"))
                for k, v in data.get("rates", {}).items():
                    rates[date.fromisoformat(k)] = float(v["USD"])
                break
            except Exception as e:  # noqa: BLE001
                log(f"  Wechselkurs {year} über {base} fehlgeschlagen: {e}")
        time.sleep(0.3)
    return rates


# ------------------------------------------------------------------ Metallkurse (USD je Unze)

def from_stooq(sym):
    text = http(f"https://stooq.com/q/d/l/?s={sym.lower()}usd&i=d", {"Accept": "text/csv"})
    out = {}
    for row in csv.DictReader(io.StringIO(text)):
        try:
            d = date.fromisoformat(row["Date"])
            p = float(row["Close"])
        except (KeyError, ValueError, TypeError):
            continue
        if p > 0:
            out[d] = p
    return out


def from_yahoo(sym):
    q = urllib.parse.urlencode({
        "period1": ts(date(1999, 1, 1)),
        "period2": ts(datetime.now(timezone.utc).date() + timedelta(days=1)),
        "interval": "1d",
        "events": "history",
    })
    out = {}
    for host in ("query1", "query2"):
        try:
            data = json.loads(http(f"https://{host}.finance.yahoo.com/v8/finance/chart/{urllib.parse.quote(YAHOO[sym])}?{q}",
                                   {"Accept": "application/json"}))
            res = data["chart"]["result"][0]
            stamps = res.get("timestamp") or []
            closes = res["indicators"]["quote"][0].get("close") or []
            for t, c in zip(stamps, closes):
                if c is not None and c > 0:
                    out[datetime.fromtimestamp(t, tz=timezone.utc).date()] = float(c)
            if out:
                return out
        except Exception as e:  # noqa: BLE001
            log(f"  Yahoo {host} {sym}: {e}")
    return out


def from_goldapi(sym, start, end):
    out = {}
    for (a, b, group) in ((start, end - timedelta(days=5 * 365), "month"), (end - timedelta(days=5 * 365), end, "day")):
        q = urllib.parse.urlencode({
            "symbol": sym, "startTimestamp": ts(a), "endTimestamp": ts(b),
            "groupBy": group, "aggregation": "avg", "orderBy": "asc",
        })
        data = json.loads(http("https://api.gold-api.com/history?" + q, {"x-api-key": KEY, "Accept": "application/json"}))
        rows = data if isinstance(data, list) else next((data[k] for k in ("data", "history", "results", "prices", "items")
                                                         if isinstance(data, dict) and isinstance(data.get(k), list)), [])
        for r in rows:
            if not isinstance(r, dict):
                continue
            period = str(r.get(group, r.get("date", r.get("timestamp"))))
            try:
                d = date.fromisoformat(period[:10]) if len(period) >= 10 else date(int(period[:4]), int(period[5:7]), 1)
                p = float(r.get("avg_price", r.get("price")))
            except (TypeError, ValueError):
                continue
            if p > 0:
                out[d] = p
        time.sleep(3)
    return out


def gold_monthly():
    text = http("https://raw.githubusercontent.com/datasets/gold-prices/main/data/monthly.csv", {"Accept": "text/csv"})
    out = {}
    for row in csv.DictReader(io.StringIO(text)):
        try:
            y, m = row["Date"][:7].split("-")
            out[date(int(y), int(m), 15)] = float(row["Price"])
        except (KeyError, ValueError, TypeError):
            pass
    return out


def plausible(series):
    """Mindestens ein paar Jahre Tageswerte."""
    return len(series) >= 500


# ------------------------------------------------------------------ Hauptprogramm

def thin(points, today):
    """Die letzten 5 Jahre täglich, davor ein Wert je Woche."""
    cut = today - timedelta(days=5 * 365 + 1)
    out = {}
    weekly = {}
    for d, p in sorted(points.items()):
        if d >= cut:
            out[d] = p
        else:
            weekly[(d.isocalendar()[0], d.isocalendar()[1])] = (d, p)
    for d, p in weekly.values():
        out[d] = p
    return dict(sorted(out.items()))


def write_log():
    os.makedirs(os.path.dirname(LOG) or ".", exist_ok=True)
    with open(LOG, "w", encoding="utf-8") as f:
        f.write(f"Lauf: {datetime.now(timezone.utc).isoformat(timespec='seconds')}\n")
        f.write("\n".join(log_lines) + "\n")


def main():
    today = datetime.now(timezone.utc).date()

    log("Lade EUR/USD-Referenzkurse der EZB …")
    rates = {}
    try:
        rates = ecb_usd_per_eur()
    except Exception as e:  # noqa: BLE001
        log(f"  EZB fehlgeschlagen: {e} – versuche frankfurter")
    if len(rates) < 1000:
        rates.update(frankfurter_usd_per_eur(EURO_START, today))
    log(f"  {len(rates)} Wechselkurse")
    if not rates:
        log("Keine Wechselkurse – vorhandene Datei bleibt.")
        write_log()
        return 0
    rate_days = sorted(rates)

    def usd_per_eur(d):
        i = bisect.bisect_right(rate_days, d) - 1
        return rates[rate_days[max(i, 0)]]

    monthly_gold = {}
    try:
        monthly_gold = gold_monthly()
        log(f"  Gold-Monatswerte: {len(monthly_gold)}")
    except Exception as e:  # noqa: BLE001
        log(f"  Gold-Monatswerte fehlgeschlagen: {e}")

    result = {}
    sources = {}
    for sym in SYMBOLS:
        series = {}
        attempts = []
        if KEY:
            attempts.append(("gold-api.com", lambda s=sym: from_goldapi(s, EURO_START, today + timedelta(days=1))))
        attempts += [("Stooq", lambda s=sym: from_stooq(s)), ("Yahoo", lambda s=sym: from_yahoo(s))]
        for name, fn in attempts:
            try:
                part = fn()
            except Exception as e:  # noqa: BLE001
                log(f"  {sym} {name}: fehlgeschlagen ({e})")
                continue
            log(f"  {sym} {name}: {len(part)} Werte" + (f", {min(part)} bis {max(part)}" if part else ""))
            if plausible(part):
                recent = max(part) >= today - timedelta(days=21)
                if recent:
                    series = {**series, **part}  # ältere Werte aus vorheriger Quelle behalten, neue Quelle gewinnt
                    sources[sym] = name if sym not in sources else f"{sources[sym]} + {name}"
                    break  # aktuell genug
                if len(part) > len(series):
                    series = dict(part)
                    sources[sym] = name
            time.sleep(1)

        if sym == "XAU" and monthly_gold:
            first = min(series) if series else today
            for d, p in monthly_gold.items():
                if EURO_START <= d < first:
                    series[d] = p

        series = {d: p for d, p in series.items() if d >= EURO_START}
        series = thin(series, today)
        result[sym] = [[(d - EPOCH).days, round(p / usd_per_eur(d), 2)] for d, p in series.items()]
        log(f"  {sym}: {len(result[sym])} Punkte in EUR")

    if not any(result.values()):
        log("Keine Kursdaten erhalten – vorhandene Datei bleibt unverändert.")
        write_log()
        return 0

    # Bestehende Reihen nicht durch schlechtere ersetzen
    try:
        with open(OUT, encoding="utf-8") as f:
            old = json.load(f).get("metals", {})
        for sym in SYMBOLS:
            if len(result.get(sym, [])) < len(old.get(sym, [])) * 0.8:
                log(f"  {sym}: neue Reihe kürzer als vorhandene – behalte vorhandene")
                result[sym] = old[sym]
    except Exception:  # noqa: BLE001
        pass

    os.makedirs(os.path.dirname(OUT) or ".", exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump({"generated": today.isoformat(), "currency": "EUR", "unit": "oz",
                   "sources": sources, "metals": result}, f, separators=(",", ":"))
    log(f"Geschrieben: {OUT} ({sum(len(v) for v in result.values())} Werte)")
    write_log()
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as e:  # noqa: BLE001
        log(f"Unerwarteter Fehler: {e}")
        write_log()
        sys.exit(0)
