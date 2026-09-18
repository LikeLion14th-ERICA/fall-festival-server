'use strict';

// Requests from "Try it out" go to this server. Operations marked
// "[서버 미구현]" exist only in the API v2 mock server.
window.addEventListener('load', () => {
  window.ui = SwaggerUIBundle({
    url: '/docs/openapi.json',
    dom_id: '#swagger-ui',
    deepLinking: true,
    displayRequestDuration: true,
    persistAuthorization: false,
    tryItOutEnabled: true,
  });
});
