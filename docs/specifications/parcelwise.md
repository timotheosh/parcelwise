# Parcelwise — Shipping Quote Comparison Application

## 1. Objective

Build a full-stack shipping quote application named Parcelwise using the existing project's Clojure, Luminus, Reitit, Swagger/OpenAPI, ClojureScript, Reagent, and Shadow CLJS configuration.

Parcelwise accepts an origin address, destination address, and parcel description. It retrieves available shipping rates, normalizes them into a stable application-owned format, and displays them sorted by total price.

The application must support:

1. A deterministic fake carrier adapter for development and automated testing.
2. A Shippo adapter using Shippo test credentials.
3. A Reagent interface for entering shipment details and viewing quotes.

Follow the multi-agent workflow defined in `.claude/CLAUDE.md`.

## 2. User Story

As a person preparing a parcel for shipment, I want to enter the origin, destination, weight, and dimensions so that I can compare available shipping services by price and estimated delivery time.

## 3. Initial Scope

The initial version must provide:

* A browser-based quote form.
* Server-side request validation.
* A stable application-owned quote model.
* Deterministic fake quotes for tests and local development.
* Optional Shippo test-mode integration.
* Quote sorting by total price.
* A clear distinction between actions, calculations, and data.
* OpenAPI documentation for the application API.
* Automated backend tests for business behavior.
* Automated tests for important frontend calculations and transformations where practical.
* A Draw.io flow diagram after implementation approval.

## 4. Explicitly Out of Scope

Do not implement:

* User accounts or authentication.
* Persistent storage.
* Shipping-label purchases.
* Real financial transactions.
* Production Shippo credentials.
* Package tracking.
* Address autocomplete.
* Saved addresses or shipment history.
* International customs declarations.
* Multiple parcels in one shipment.
* Administrative configuration screens.
* A general-purpose carrier framework beyond what the two required adapters need.
* Deployment infrastructure.

Do not add these capabilities speculatively.

## 5. Architecture

Use the following conceptual flow:

```
Reagent quote form
        |
        v
POST /api/quotes
        |
        v
Request parsing and boundary validation
        |
        v
Application quote service
        |
        +--> Carrier adapter — Action
        |       |
        |       +--> Fake carrier
        |       |
        |       +--> Shippo test API
        |
        v
Response normalization — Calculation
        |
        v
Filtering and ranking — Calculations
        |
        v
Stable quote response — Data
        |
        v
Reagent results view
```

### 5.1 Action / Calculation / Data constraints

Follow the Action / Calculation / Data guidance included in the implementer prompt.
In particular:

* HTTP requests are actions.
* Reading environment variables is an action.
* Calling Shippo is an action.
* Logging is an action.
* Parsing an HTTP request at the boundary is an action.
* Dimensional-weight calculation is a calculation.
* Billable-weight calculation is a calculation.
* Normalizing a carrier response is a calculation.
* Filtering unusable quotes is a calculation.
* Sorting and ranking quotes is a calculation.
* Addresses, parcels, carrier responses, normalized quotes, and validation errors are data.

Calculations must:

* Depend only on explicit arguments.
* Return explicit values.
* Avoid mutation.
* Avoid I/O, logging, clocks, environment access, and network calls.
* Be independently testable.

Keep actions thin and push business decisions into calculations.

## 6. Domain Model

### 6.1 Address

An address contains:

```
{:name         "Jane Example"
 :street-1     "123 Market Street"
 :street-2     nil
 :city         "Philadelphia"
 :region       "PA"
 :postal-code  "19103"
 :country-code "US"}
```

Required fields:

* `name`
* `street-1`
* `city`
* `region`
* `postal-code`
* `country-code`

Rules:

* Required string fields must not be blank.
* `country-code` must be a two-character uppercase ISO-style country code.
* The initial UI may default `country-code` to `"US"`.
* `street-2` is optional.
* Do not attempt comprehensive postal-address validation in the initial version.

### 6.2 Parcel

A parcel contains:

```
{:weight-kg  3.2M
 :length-cm 30.0M
 :width-cm  20.0M
 :height-cm 15.0M}
```

Rules:

* Every value is required.
* Every value must be numeric, finite, and greater than zero.
* Reject zero and negative values.
* Reject malformed numeric strings at the HTTP boundary.
* Internal calculations must not use binary floating-point for money.
* Unit conversion must occur in an explicit calculation before calling a carrier API.

### 6.3 Quote request

```
{:origin      <address>
 :destination <address>
 :parcel      <parcel>}
```

### 6.4 Normalized quote

```
{:id                 "fake-ground"
 :carrier            "Example Carrier"
 :service-code       "ground"
 :service-name       "Ground"
 :currency           "USD"
 :base-amount         12.50M
 :surcharge-amount     1.25M
 :total-amount        13.75M
 :estimated-days      4
 :billable-weight-kg   3.2M
 :provider            :fake}
```

Required response fields:

* `id`
* `carrier`
* `service-code`
* `service-name`
* `currency`
* `base-amount`
* `surcharge-amount`
* `total-amount`
* `billable-weight-kg`
* `provider`

`estimated-days` may be absent when the provider does not supply it.
The browser API must encode monetary decimal values as strings:

```json
{
  "baseAmount": "12.50",
  "surchargeAmount": "1.25",
  "totalAmount": "13.75"
}
```

Do not expose Java or Clojure implementation-specific number representations.

## 7. Business Calculations

### 7.1 Dimensional weight

Calculate dimensional weight in kilograms as:

```
(length-cm × width-cm × height-cm) ÷ 5000
```

The divisor must be a named domain constant, not an unexplained literal distributed through the code.

### 7.2 Billable weight

```
billable-weight = max(actual-weight, dimensional-weight)
```

Preserve sufficient precision for the carrier request. Do not round prematurely.

### 7.3 Quote normalization

Carrier-specific responses must be transformed into the normalized quote model before they reach route handlers or UI code.
Provider-specific field names must remain inside the adapter or normalization boundary.

### 7.4 Quote filtering

Exclude a provider result when:

* It has no stable identifier.
* Carrier or service information is missing.
* Currency is missing.
* Total price is missing or invalid.
* Total price is negative.
* The provider explicitly reports the rate as unavailable.

One malformed provider rate must not invalidate every valid rate returned in the same provider response.

### 7.5 Quote ordering

Sort valid quotes by:

1. `total-amount`, lowest first.
2. `estimated-days`, lowest first, with missing estimates after known estimates.
3. `carrier`, alphabetically.
4. `service-name`, alphabetically.
5. `id`, as a deterministic final tie-breaker.

The same input collection must always produce the same ordering.

## 8. Carrier Boundary

Define a narrow carrier abstraction based on the application's actual need:

```
(fetch-rates carrier request)
```

The result must distinguish successful provider data from provider failure without leaking provider-specific data into the domain layer.
Do not build a broad carrier framework or introduce abstractions for hypothetical future operations.

### 8.1 Fake carrier adapter

The fake adapter must:

* Be the default in development and automated tests.
* Return deterministic data.
* Make no network calls.
* Return at least three services with different prices and delivery estimates.
* Include at least one rate that exercises dimensional weight.
* Be configurable to return a provider failure for tests.
* Be configurable to include a malformed rate so filtering can be tested.

The fake adapter's results should be realistic enough to exercise normalization and ranking.

### 8.2 Shippo adapter

The Shippo adapter must:

* Use the Shippo REST API.
* Use test credentials only.
* Read the token from `SHIPPO_API_TOKEN`.
* Never send the token to the browser.
* Never log the token.
* Never store the token in source control.
* Use explicit connection and request timeouts.
* Convert kilograms and centimeters into units required by Shippo.
* Map application addresses and parcel data to the Shippo request.
* Normalize Shippo rates into the application quote model.
* Map Shippo authentication, validation, timeout, rate-limit, and upstream failures into stable application errors.
* Avoid purchasing labels or creating transactions beyond what is required to retrieve rates.

Select the adapter using:

```
CARRIER_PROVIDER=fake
```

or:

```
CARRIER_PROVIDER=shippo
```

Default to `fake` when the value is absent in development and test environments.
If `shippo` is selected without `SHIPPO_API_TOKEN`, fail at application startup with a useful message that does not expose secrets.

## 9. HTTP API

### 9.1 Create quotes

```
POST /api/quotes
Content-Type: application/json
```

Example request:

```json
{
  "origin": {
    "name": "Jane Example",
    "street1": "123 Market Street",
    "street2": null,
    "city": "Philadelphia",
    "region": "PA",
    "postalCode": "19103",
    "countryCode": "US"
  },
  "destination": {
    "name": "John Example",
    "street1": "500 Howard Street",
    "street2": null,
    "city": "San Francisco",
    "region": "CA",
    "postalCode": "94105",
    "countryCode": "US"
  },
  "parcel": {
    "weightKg": "3.2",
    "lengthCm": "30",
    "widthCm": "20",
    "heightCm": "15"
  }
}
```

Successful response:

```
HTTP/1.1 200 OK
Content-Type: application/json
```

```json
{
  "quotes": [
    {
      "id": "fake-ground",
      "carrier": "Example Carrier",
      "serviceCode": "ground",
      "serviceName": "Ground",
      "currency": "USD",
      "baseAmount": "12.50",
      "surchargeAmount": "1.25",
      "totalAmount": "13.75",
      "estimatedDays": 4,
      "billableWeightKg": "3.2",
      "provider": "fake"
    }
  ]
}
```

### 9.2 Validation failure

Return HTTP `400`:

```json
{
  "error": {
    "code": "invalid-request",
    "message": "The quote request is invalid.",
    "fields": {
      "parcel.weightKg": ["must be greater than zero"]
    }
  }
}
```

Requirements:

* Field paths must be stable.
* Do not return stack traces.
* Return all useful boundary-validation errors discovered in one pass when practical.

### 9.3 Provider unavailable

Return HTTP `502`:

```json
{
  "error": {
    "code": "carrier-unavailable",
    "message": "Shipping rates are temporarily unavailable."
  }
}
```

Do not expose raw provider responses, credentials, internal exception names, or stack traces.

### 9.4 Provider timeout

Return HTTP `504`:

```json
{
  "error": {
    "code": "carrier-timeout",
    "message": "The shipping-rate request timed out."
  }
}
```

### 9.5 No valid rates

If the provider request succeeds but yields no usable rates, return HTTP `200`:

```json
{
  "quotes": []
}
```

The UI must present this as a valid empty result rather than an application error.

### 9.6 OpenAPI

Document `/api/quotes` through the project's Swagger/OpenAPI support, including:

* Request schema.
* Successful response schema.
* Validation response.
* Provider-unavailable response.
* Timeout response.
* Example payloads.

The documentation must describe the application-owned API, not merely link to Shippo.

## 10. Reagent User Interface

Create a single-page interface containing the following elements.

### 10.1 Quote form

Sections:

* Origin address.
* Destination address.
* Parcel measurements.
* Submit button.

Behavior:

* All required fields are labeled.
* Units are visible beside parcel fields.
* The form prevents accidental double submission while a request is active.
* Client-side validation may provide immediate feedback, but server validation remains authoritative.
* Pressing Submit sends the request to `/api/quotes`.

### 10.2 Loading state

While the request is active:

* Disable the submit button.
* Display a visible loading message.
* Preserve entered form values.

### 10.3 Results

Display valid quotes in ascending price order.
Each quote shows:

* Carrier.
* Service name.
* Total price and currency.
* Estimated delivery days when available.
* Billable weight.

Do not expose base price and surcharge by default unless a simple expandable detail view is implemented.

### 10.4 Empty state

When no valid rates are returned, show:
No shipping services are available for this shipment.

### 10.5 Error state

Show understandable messages for:

* Invalid form data.
* Carrier unavailable.
* Carrier timeout.
* Unexpected server failure.

Field errors must appear near their corresponding inputs when possible.
Do not display raw exceptions or provider payloads.

### 10.6 Accessibility

At minimum:

* Every input has an associated label.
* The form is usable by keyboard.
* Loading and error messages are exposed as status or alert content.
* Focus moves to the error summary after a failed submission.
* Color is not the only indicator of error state.

Visual polish is secondary to correctness and usability.

## 11. Validation and Contracts

Create explicit specs or established schemas for:

* Address.
* Parcel.
* Quote request.
* Normalized quote.
* API success response.
* API error response.
* Carrier adapter result.

Validate data at meaningful boundaries:

* Incoming HTTP request.
* Carrier-response normalization boundary.
* Outgoing application API response where useful.

Do not spec every local binding or trivial intermediate value.

## 12. Logging and Security

* Never log authorization headers or API tokens.
* Never commit `.env`, credentials, or test tokens.
* Provide a checked-in example environment file containing names but no values.
* Log provider failures with a correlation identifier and safe metadata.
* Return the correlation identifier to the client for unexpected failures.
* Sanitize provider error messages before logging when they may include submitted addresses.
* Treat addresses as sensitive user-provided information.
* Do not log complete addresses in normal application logs.
* Apply reasonable request-body size limits.
* Use server-side timeouts for external calls.

## 13. Testing Requirements

### 13.1 Test-designer responsibilities

Before production implementation, independently create behavioral tests covering:

* Valid quote request.
* Required-field validation.
* Zero and negative parcel measurements.
* Dimensional weight greater than actual weight.
* Actual weight greater than dimensional weight.
* Malformed rates being filtered.
* Deterministic quote ordering.
* Missing delivery estimates sorting after known estimates at the same price.
* Provider unavailable.
* Provider timeout.
* Empty valid-rate result.
* Stable API error shapes.
* Prevention of secret exposure where testable.

Demonstrate RED for the intended missing behavior.

### 13.2 Calculation tests

Directly test:

* Dimensional-weight calculation.
* Billable-weight calculation.
* Unit conversion.
* Quote normalization.
* Quote filtering.
* Quote ordering.
* Monetary serialization.

Use table-driven tests when several meaningful boundary examples share the same behavior.

### 13.3 Route tests

Test `/api/quotes` with the fake adapter.
Route tests must verify:

* Status code.
* Response content type.
* Stable response shape.
* Field-path validation errors.
* Mapping of carrier failures to application errors.

Do not make live Shippo calls in the normal automated test suite.

### 13.4 Adapter contract tests

Define shared behavioral expectations that both fake and Shippo adapters must satisfy where practical.
The Shippo adapter may use recorded or representative provider fixtures for normalization tests. Remove secrets and personal address information from fixtures.

### 13.5 Frontend tests

Test important frontend behavior where the generated project tooling supports it without disproportionate setup:

* Request construction.
* Response decoding.
* State transition from idle to loading to success.
* State transition from loading to error.
* Quote rendering order.
* Field-error mapping.

Do not over-test static markup or incidental component structure.

## 14. Acceptance Criteria

**AC1 — Valid quote request**
Given valid origin, destination, and parcel data
When the user requests quotes using the fake provider
Then the server returns HTTP `200`
And at least three normalized quotes are returned
And the quotes are sorted according to the documented ordering rules.

**AC2 — Invalid parcel**
Given a parcel with zero or negative weight or dimensions
When quotes are requested
Then the server returns HTTP `400`
And the response identifies every invalid parcel field
And the carrier adapter is not called.

**AC3 — Dimensional weight**
Given a parcel whose dimensional weight exceeds its actual weight
When quotes are calculated
Then its billable weight equals its dimensional weight.

**AC4 — Actual weight**
Given a parcel whose actual weight exceeds its dimensional weight
When quotes are calculated
Then its billable weight equals its actual weight.

**AC5 — Malformed provider rate**
Given a provider response containing valid and malformed rates
When the response is normalized
Then malformed rates are excluded
And valid rates are still returned.

**AC6 — Provider failure**
Given that the carrier adapter reports an upstream failure
When quotes are requested
Then the API returns the corresponding stable application error
And raw provider details are not exposed.

**AC7 — Empty rates**
Given a successful provider response with no usable rates
When quotes are requested
Then the API returns HTTP `200` with an empty quote list
And the UI displays the empty-state message.

**AC8 — UI submission**
Given valid form values
When the user submits the form
Then the button is disabled during the request
And a loading status is displayed
And returned quotes are shown in price order.

**AC9 — Validation presentation**
Given invalid form values
When the server returns field errors
Then errors appear by the corresponding fields
And focus moves to an error summary.

**AC10 — Workflow completion**
The implementation is complete only when:

* RED was independently demonstrated.
* The relevant test suite is GREEN.
* Calculation and business behavior has meaningful coverage.
* Boundary data has appropriate specs or schemas.
* Correctness review approves the final revision.
* Structural review approves the same revision.
* The final Draw.io diagram reflects the approved implementation.

## 15. Suggested Delivery Sequence

Implement one independently reviewed vertical slice at a time.

**Slice 1 — Pure domain calculations**

* Address and parcel contracts.
* Dimensional weight.
* Billable weight.
* Normalized quote model.
* Quote filtering and ordering.

**Slice 2 — Fake-provider API**

* Carrier boundary.
* Deterministic fake adapter.
* `/api/quotes`.
* Validation and stable error responses.
* OpenAPI documentation.

**Slice 3 — Reagent interface**

* Form.
* Loading state.
* Results.
* Validation display.
* Empty and failure states.

**Slice 4 — Shippo test adapter**

* Environment configuration.
* Unit conversion.
* Shippo request mapping.
* Shippo response normalization.
* Timeouts and error mapping.
* Fixture-based adapter tests.

Each slice must pass the complete test-design, implementation, dual-review, and documentation workflow before the next slice begins.

## 16. Definition of Done

Parcelwise is done when:

* The fake-provider path works end to end.
* The Shippo test adapter can retrieve and normalize test rates with valid credentials.
* No normal automated test requires network access.
* The API is documented with OpenAPI.
* Required validation and error behavior is implemented.
* Calculations are pure and directly tested.
* External calls and environment access remain at action boundaries.
* Important data boundaries have explicit contracts.
* Relevant backend and frontend tests pass.
* No secrets or complete addresses appear in logs or source control.
* Both independent reviewers approve the same revision.
* The approved runtime flow is documented in Draw.io.
* Durable workflow state records completion.
