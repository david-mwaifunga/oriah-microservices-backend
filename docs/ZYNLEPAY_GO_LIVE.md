# ZynlePay: from the stand-in to real money

Phase 13 built payment through ZynlePay (mobile money and cards) **without a ZynlePay account**. Oriah has not signed up as a merchant yet, and
ZynlePay's official API documents sit behind its sandbox login. Everything the code knows about ZynlePay's API came from two unofficial code
libraries written by other people. So the code was built against **our own stand-in** for ZynlePay, and every part of ZynlePay's API that we could
not confirm is a **setting, not code**.

This file is the list of things to do, in order, before a customer's real money goes through it. Nothing here can be skipped: the payment service
**refuses to start** in sandbox or live mode until the settings below are filled in.

**Words used here.** *Sandbox*: ZynlePay's own test system, where no real money moves. *Callback*: a message ZynlePay sends to our server to say
"this payment is done". *Status call*: our server asking ZynlePay "what became of payment X?". *Tariff*: the fee ZynlePay takes from each payment.

---

## 1. What the owner must do first (nothing technical)

1. **Sign up with ZynlePay as a merchant** and ask for sandbox access. Confirm at sign-up that **cards are accepted** for Oriah (what we know about
   card acceptance is second-hand).
2. **Get the tariff in writing**: for MTN, Airtel, Zamtel and cards, the percentage ZynlePay takes and any fixed amount per payment. ZynlePay's
   answers do not say what fee was taken, so the system works the fee out from this tariff. Example with the placeholder tariff the stand-in uses
   (2.5% for mobile money): a customer pays K180.00; fee = K180.00 x 2.5% = K4.50; Oriah receives K180.00 - K4.50 = K175.50. If the real tariff
   differs, every fee in the books would be wrong, which is why the real one must be typed in.
3. **Ask ZynlePay the questions in section 5** and keep the answers.

## 2. Prove the API in ZynlePay's sandbox (a developer, about a day)

Run a stack that is **not live** with `oriah.payment.zynlepay.mode: SANDBOX` and the three sandbox credentials in the payment container's
environment (never in a file that is committed):

| Environment variable | What it is |
|---|---|
| `ORIAH_ZYNLEPAY_SANDBOX_MERCHANT_ID` | the sandbox merchant id |
| `ORIAH_ZYNLEPAY_SANDBOX_API_ID` | the sandbox API id |
| `ORIAH_ZYNLEPAY_SANDBOX_API_KEY` | the sandbox API key |

The sandbox address is fixed in code (`https://sandbox.zynlepay.com`). Then fill in, in `config-repo/payment.yaml`, what the sandbox proves. None of
these has a default:

| Setting (`oriah.payment.zynlepay.`) | What to find out | What the unofficial libraries say |
|---|---|---|
| `wire.api-path` | the path of the one JSON endpoint | `/zynlepay/jsonapi` |
| `wire.status-style` | `PATH` (a separate status address with a flat body) or `METHOD` (the same endpoint with a method name) | both are described; one of them is right |
| `wire.status-path` | the status address, when the style is `PATH` | `/zynlepay/jsonapi/paymentstatus` |
| `wire.redirect-hosts` | the host names ZynlePay's card page may be on | unknown |
| `wire.return-url` | the https page of the shop the customer comes back to after the card page, with `{orderId}` and `{id}` | ours to choose |
| `service-id`, `callback-url` | whether ZynlePay wants a service id, and where it should send callbacks | unknown |
| `codes.*` | what each response code means: started (`120`), paid (`100`), pending (`990`), failed (`995`), credentials refused (`9900-9915`) | as shown |
| `codes.refused` | **which refusals of a new payment are final** (the customer was never asked) | unknown: empty |
| `codes.not-found`, `codes.ambiguous` | the code for "no such payment", and any code that means "ask again later" | unknown: empty |
| `fees.MTN`, `fees.AIRTEL`, `fees.ZAMTEL`, `fees.CARD` | `percent` and `fixed`, from the written tariff | not in any library |
| `wallet-prefixes` | the first two digits of each network's numbers, if ZynlePay routes differently from the usual 96/76, 97/77, 95/75 | the usual ones |

**While `codes.refused` is empty, a refusal by ZynlePay is treated as "unclear"**, never as a failure: the payment waits, is asked about, and goes to
the owner if ZynlePay never settles it. That is safe (no money is lost and nobody is told "failed" wrongly) but slow for the customer, so fill it in.

Check each of these in the sandbox and write the date down:

- [ ] A mobile-money payment that the customer approves: our status call answers "paid", the amount ZynlePay reports equals the amount asked, and
      the order becomes PAID.
- [ ] One the customer refuses, and one the customer ignores until it times out: which code each gives.
- [ ] A card payment: the address of the card page (its host goes in `redirect-hosts`), and that the customer comes back to `return-url`.
- [ ] The status call for a reference ZynlePay has never seen: which code (it goes in `codes.not-found`).
- [ ] Whether the callback carries anything that proves it came from ZynlePay (a signature or a secret). **We do not rely on it either way**: a
      callback only makes our server ask ZynlePay sooner. If there is a signature, checking it is a small addition.
- [ ] Whether ZynlePay reports the amount in Kwacha with two decimals, and in which field.
- [ ] Whether the same reference sent twice makes one payment or two (we never send one twice on purpose; a lost answer is asked about, not re-sent).
- [ ] The fee on ZynlePay's statement for each test payment equals what the tariff gives.

## 3. Going live

On the production stack only, in the payment container's environment:

| Environment variable | What it is |
|---|---|
| `ORIAH_ZYNLEPAY_LIVE` | exactly the word `true`. Only this makes a stack live; no settings file can |
| `ORIAH_ZYNLEPAY_MERCHANT_ID`, `ORIAH_ZYNLEPAY_API_ID`, `ORIAH_ZYNLEPAY_API_KEY` | the production credentials |
| `ORIAH_ZYNLEPAY_WIRE_CHECKED` | the date section 2 was completed, for example `2027-01-15` |

The production address is fixed in code (`https://payments.zynlepay.com`). The service **will not start** if: any of these is missing on a live
stack; a sandbox credential is present on a live stack; a production credential is present on a stack that is not live; or any setting of section 2
is empty. The stand-in for ZynlePay does not exist on a live stack. `scripts/check_service_registry.py` refuses a development compose file that
names any of these variables.

Also:

- [ ] ZynlePay is told our callback address: `https://<the shop's address>/api/v1/payments/zynlepay/callback`. It is open to anyone on purpose
      (ZynlePay cannot sign in), takes at most 8 KB, is rate-limited, and can only make our server ask ZynlePay a question.
- [ ] The gateway's address for the storefront is in `wire.return-url`.
- [ ] The poll settings are the production ones (the development stack shortens them): asked every 15 seconds at first, then every minute; a payment
      ZynlePay has not settled after 30 minutes goes to the owner.
- [ ] The hourly limits are the production ones: 6 payments started per customer and 4 per phone number in an hour.
- [ ] The alert mailboxes of Phase 11 are set, so staff are emailed when ZynlePay does not settle a payment.
- [ ] One real payment of a small amount by each network and by card, by the owner, refunded by hand afterwards.

## 4. What staff must know

- **A payment that says "waiting" is not unpaid.** Do not ask the customer to pay again, and do not record the money by hand: the system refuses a
  hand-keyed payment for an order that has a ZynlePay payment in flight.
- **"ZynlePay did not settle this payment"** (an alert, and the list at `GET /api/v1/admin/payments/provider-payments?status=UNRESOLVED`): look the
  payment up on ZynlePay's own merchant page by its reference. Sales staff may press "check now". **Only the owner** says "it was paid" or "it was not
  paid", with a note of what she saw.
- **Refunds are paid by hand**, as in Phase 12: ZynlePay has no refund API that we know of. The refund owed appears in the same list as any other.
- **Each month**, put ZynlePay's statement total beside the system's (finance's statement check, provider `ZYNLEPAY`). If ZynlePay took a different
  fee from the tariff, the difference shows there.

## 5. Questions for ZynlePay

1. What is the fee for each of MTN, Airtel, Zamtel and cards (percentage and any fixed part)? Is it taken from each payment, or billed monthly?
2. Which response codes mean a new payment was refused for good (so the customer can be told at once)?
3. Is there a signature or shared secret on callbacks? From which addresses do they come? How many times is a callback retried?
4. How long does a mobile-money prompt stay on the customer's phone before it times out?
5. Is there a refund API? If not, how is a refund done?
6. When is collected money paid to Oriah's bank account, and is there a settlement report we can download?
7. Is there a smallest and a largest amount per payment, per network?
8. For cards: which cards are accepted, is 3-D Secure used, and what are the host names of the card page?
9. Can a payment be looked up by our own reference on the merchant page?
10. Is there a limit on how often we may ask for a payment's status?
