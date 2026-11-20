# Zhouzhang

English | [简体中文](README.zh-CN.md)

Zhouzhang (周账, roughly "weekly ledger") is an Android app that reads the official WeChat Pay and Alipay bill exports and turns them into weekly and monthly totals: how much went out, and where it went.

The data stays on the phone. There is no account to create and no server to trust. The app makes no network requests unless you turn on email import, and even then it only talks to the IMAP server you enter yourself.

**Status:** v0.5.0. The database is encrypted with SQLCipher, keyed from the Android Keystore.

## Why another bill app

Most tools that merge WeChat and Alipay bills break on the same thing: Alipay changed its export format.

Since 2023, "apply for transaction statement" gives you a 12-column CSV. A lot of open-source tools, including some with a few thousand stars, still hard-code the 17-column header and column indexes from the old format. They don't fail loudly. They just report wrong totals, which is harder to notice than a crash.

WeChat changed as well, to xlsx in 2026. The time column is an Excel serial number (`46344.655...`) instead of a string, and amounts are numbers instead of `¥32.00`.

Both parsers were written against the real files and checked against them: 63 rows from a WeChat statement and 91 from an Alipay one, each matching the row count the file declares about itself.

## What it does

### Import

| Input | Notes |
|---|---|
| WeChat xlsx / csv | Skips the 17-line preamble; restores Excel serial timestamps |
| Alipay csv (new, 12 columns) | Decoded as GB18030 |
| Alipay csv (old, 17 columns) | Decoded as GBK; handles full-width parentheses in headers |
| Encrypted zip | Asks for the password, extracts in memory, writes nothing to disk |

You can select several files at once and import WeChat and Alipay together. Imports can be undone: row ids only increase, so deleting the rows an import added rolls back exactly that batch.

The empty state tells you where both export screens are, so you don't have to go looking for the instructions.

### Email import

Enter an IMAP account and app password once. When a statement email arrives, tap "Fetch" and the attachment goes into the database, no digging through folders for attachments. The app password and any zip password are encrypted with the Keystore.

### Cleaning

Skip this and spending comes out 30% or more too high.

- Transfers, red packets, withdrawals and group collections are not spending
- Yu'ebao, Lingqiantong and fund purchases are not spending
- Huabei, Jiebei and credit card repayments are not spending
- Refunds are flagged separately and kept out of the totals
- Rows are deduplicated by transaction id, so importing the same statement twice doesn't double anything

### Categories

Keyword rules first, with Alipay's own transaction category as a fallback when the merchant name tells you nothing.

What's left over is easy to fix. "Tidy categories" lists uncategorised merchants by amount; pick a category for one and the app creates a rule and recalculates history. Similar merchants hit the new rule from then on.

### Views

- Week view: Monday to Sunday by default, any start day in settings. Swipe between weeks. The comparison uses the same days of the previous week, not the full previous week.
- Month view: calendar month, category share, top merchants, change from last month.
- After an import the app jumps to the most recent week that has data. Landing on an empty current week makes a successful import look like a failed one.

## Privacy

The selling point is that data doesn't leave the device, so the app tries to make that checkable instead of just stating it.

- `INTERNET` is used for email import only. With no mailbox configured, the app makes no network requests. With one configured, it connects directly to that IMAP server (TLS 1.2+ enforced, hostname verified). Nothing is relayed through a third party.
- Screenshots are blocked. Screenshots, screen recordings and the recent-apps thumbnail all come out blank.
- The database is encrypted with SQLCipher (AES-256). The 32-byte passphrase is wrapped by an AES key in the Android Keystore; it never appears in code or in plain configuration.
- `networkSecurityConfig` disables cleartext traffic and trusts only system roots, so a user-installed certificate can't read the app password.
- No analytics SDK, no ad SDK, no crash reporting.
- Zip extraction happens in memory, with hard caps on expanded size, entry count, rows and columns, so a zip bomb can't take the app down.
- `allowBackup=false`, so the database stays out of cloud backups.

The database file no longer begins with `SQLite format 3`. Open it with any SQLite tool and you get garbage. You can check that yourself.

### Known limitations

- No app lock (biometric or PIN). It was evaluated, dropped, and isn't planned.
- Category rules are thin, so the uncategorised share can be high.
- A rooted device that can inject into the process can still get around the Keystore. That applies to every local encryption scheme.
- Automatic tracking through an accessibility service or notification listener is not implemented. It's an approach that breaks with OS updates.

## Exporting your bills

**WeChat:** Me → Services → Wallet → Bills → Common questions → Download bill → For personal reconciliation → enter email and payment password. You get an encrypted zip; the password arrives as a message from the WeChat Pay official account.

**Alipay:** Me → Bills → "…" (top right) → Apply for transaction statement → For personal reconciliation → the zip is sent to your email.

## Build

JDK 17 and the Android SDK (compileSdk 34).

```bash
./gradlew assembleDebug
# output: app/build/outputs/apk/debug/app-debug.apk
```

The first build downloads Gradle and the whole dependency set. On a slow connection in mainland China, you can point `distributionUrl` in `gradle/wrapper/gradle-wrapper.properties` at `https://mirrors.cloud.tencent.com/gradle/gradle-8.7-bin.zip`, and add Aliyun mirrors for the dependency repositories.

Install on a device:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Tests

```bash
./gradlew testDebugUnitTest
```

The parsing layer is plain Kotlin with no Android dependencies, so every format case runs on the JVM without an emulator. That's how the format handling gets tested at all.

21 cases cover Excel serial timestamps, GB18030 decoding, malformed quotes, neutral-transaction detection, thousands separators, week start calculation, and input limits.

To test against your own statements, put them in `samples/private/` (already in .gitignore) and the suite picks them up.

## Layout

```
app/src/main/java/app/zhouzhang/
  core/          parsing, plain Kotlin, no Android dependencies
    Csv.kt       lenient CSV reader (stray quotes don't swallow rows)
    Xlsx.kt      minimal xlsx reader, no POI
    ZipUtil.kt   zip4j streaming decryption, nothing written to disk
    Parser.kt    format detection, field normalisation, cleaning
    Limits.kt    input size and row/column caps
  data/          SQLite and key storage
  mail/          IMAP client
  ui/            Compose theme
design/icon/     icon sources and Android resources
tools/           icon and sample generators (reproducible)
samples/         synthetic test data, no real bills
```

## Roadmap

- [x] Parser core (WeChat xlsx/csv, both Alipay formats, encrypted zip)
- [x] Week and month views
- [x] SQLCipher encryption, including migration from a plaintext database
- [x] Screenshot blocking
- ~~App lock (biometric / PIN)~~ dropped
- [x] Category rule learning: categorise an uncategorised merchant and keep the rule
- [ ] Export (redacted CSV)
- [ ] Transaction search and filters
- [ ] Bank statements

## Credits

The design borrows ideas from these projects. The code is an independent implementation; see [NOTICE](NOTICE).

- [vogo/aliwepaystat](https://github.com/vogo/aliwepaystat): field normalisation, internal transfer detection
- [yann0917/alipay-wechat-merge](https://github.com/yann0917/alipay-wechat-merge): what stray quotes do to a CSV parser
- [bingchuH/Bookkeeping](https://github.com/bingchuH/Bookkeeping): turning corrections into rules
- [mayswind/ezbookkeeping](https://github.com/mayswind/ezbookkeeping): modelling transfers

## License

[GPL-3.0](LICENSE)

The claim is that data stays on the device, and a claim like that only counts if the code can be audited. GPL keeps distributed modifications open, so nobody can add an analytics SDK, ship it, and tell the same story.
