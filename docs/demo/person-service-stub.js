// person-service, reduced to the single operation registration needs.
//
// NOT part of the deliverable. person-service is module 2 and ships no code
// yet, so without something answering on 8082 the registration scenario stops
// at its first step with 503 (Service Unavailable) and there is nothing left to
// demonstrate. This stands in for it during a walkthrough - see docs/DEMO.md.
//
// Node only, no dependencies:  node docs/demo/person-service-stub.js
//
// It answers the contract in person-service/openapi/person-service.yaml:
//   POST /api/v1/persons/registration -> 201 { "userUid": "<uuid>" }
//
// A fresh uuid per request, so every registration gets its own domain user -
// exactly as the real service would behave.

const http = require('http');
const { randomUUID } = require('crypto');

const PORT = 8082;
const PATH = '/api/v1/persons/registration';

// Addresses already handed out, so a second registration of the same one is
// refused the way the real service refuses it: 409, before Keycloak is ever
// called. That is the branch worth showing - it is why person-service goes
// first in the scenario.
const issued = new Map();

http.createServer((request, response) => {
    let raw = '';
    request.on('data', chunk => (raw += chunk));
    request.on('end', () => {
        if (request.method !== 'POST' || request.url !== PATH) {
            console.log(`${request.method} ${request.url} -> 404`);
            response.writeHead(404).end();
            return;
        }

        const email = (() => {
            try {
                return JSON.parse(raw).email;
            } catch {
                return undefined;
            }
        })();

        if (issued.has(email)) {
            console.log(`POST ${PATH} ${email} -> 409 (already registered)`);
            response.writeHead(409, { 'Content-Type': 'application/json' });
            response.end(JSON.stringify({
                timestamp: new Date().toISOString(),
                path: PATH,
                status: 409,
                error: 'USER_ALREADY_EXISTS',
                message: 'Email is already registered',
                traceId: 'stub',
                details: []
            }));
            return;
        }

        const userUid = randomUUID();
        issued.set(email, userUid);
        console.log(`POST ${PATH} ${email} -> 201 ${userUid}`);
        response.writeHead(201, { 'Content-Type': 'application/json' });
        response.end(JSON.stringify({ userUid }));
    });
}).listen(PORT, () => {
    console.log(`person-service stub listening on http://localhost:${PORT}`);
    console.log('Stop it with Ctrl+C when the walkthrough is over.');
});
