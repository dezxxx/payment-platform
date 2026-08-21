# Quest 01 - package `error`

Russian version: [01-error.ru.md](01-error.ru.md)

Read `docs/error-handling.puml` first, then answer without opening the classes
again. Answers are checked in the chat, not written here.

Legend: **R** = reason about the code, **P** = predict the output, **B** = break
it on purpose and observe.

---

## Tier 1 - the shape

1. **(R)** `ErrorCode` carries two things besides its name. Name them, and say
   which three parts of an HTTP answer are derived from them.

2. **(R)** Why is there one `ApiException` instead of `UserNotFoundException`,
   `EmailTakenException`, `InvalidPasswordException`? State the rule that
   decides when a new exception class is worth adding.

3. **(P)** A caller sends `POST /v1/auth/login` with `{"email": "not-an-email",
   "password": ""}`. Which class catches it, which `ErrorCode` is chosen, and
   what does the `details` field hold?

## Tier 2 - the two roads

4. **(R)** Name the two entry points a failure can take on its way to a
   response body, and the single condition that decides which one is used.

5. **(R)** `SecurityErrorWriter` serialises the body by hand with an
   `ObjectMapper`. `GlobalExceptionHandler` returns a `ResponseEntity` and never
   touches Jackson. Why the difference - what is present on one road and absent
   on the other?

6. **(R)** If `ErrorResponseFactory` were deleted and both roads built the
   `ErrorResponse` themselves, the code would still compile and every test we
   have would still pass. Describe the failure this class prevents anyway.

7. **(P)** `GET /v1/auth/me` with no `Authorization` header. Walk the call from
   Netty to the byte array: every class it passes through, in order.

## Tier 3 - the sharp edges

8. **(R)** `SecurityErrorWriter` ends with
   `.doOnError(ex -> DataBufferUtils.release(buffer))`. What leaks without that
   line, and why is there no such concern in `GlobalExceptionHandler`?

9. **(R)** `handleResponseStatus` covers 404, 405 and 415 with one method.
   What do those three framework exceptions have in common that makes this
   possible, and what would happen to them if the method were removed?

10. **(R)** `ErrorResponseFactory` sets `details` to `null` when the list is
    empty instead of leaving it empty. What does the client see differently,
    and which setting makes that work?

11. **(R)** `traceId` falls back to the string `"unavailable"`. Why not leave
    the field out, or send `null`? Two reasons - one from the contract, one
    from the reactive stack.

## Boss - hands on the keyboard

12. **(B)** Start the app, then run each line and predict the status **before**
    pressing enter:

    ```bash
    curl.exe -i http://localhost:8081/v1/auth/me
    curl.exe -i http://localhost:8081/v1/auth/nothing-here
    curl.exe -i -X DELETE http://localhost:8081/v1/auth/login
    curl.exe -i -X POST http://localhost:8081/v1/auth/login -H "Content-Type: text/plain" -d "x"
    curl.exe -i -X POST http://localhost:8081/v1/auth/login -H "Content-Type: application/json" -d "{"
    ```

    For each answer say which class produced the body. All five must come back
    in the same JSON shape - if one does not, that is a bug, report it.

13. **(B)** Comment out `Hooks.enableAutomaticContextPropagation()` in
    `IndividualsApiApplication`, restart, repeat the first curl. What changes in
    the body, and what does that prove about where a span lives in WebFlux?

14. **(B)** Comment out the `exceptionHandling(...)` line in `SecurityConfig`,
    restart, repeat the first curl. Describe the answer Spring Security gives on
    its own - status, body, headers - and say what our two handlers are actually
    buying us.
