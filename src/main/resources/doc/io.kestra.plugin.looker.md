The Looker plugin enables interaction with the Google Cloud Looker API 4.0 in Kestra workflows.

## Authentication

Authentication uses the Looker API 4.0 client credentials grant (`/api/4.0/login`). Pass your Looker base URL along with the API3 Client ID and Client Secret:

```yaml
baseUrl: https://yourcompany.cloud.looker.com
clientId: "{{ secret('LOOKER_CLIENT_ID') }}"
clientSecret: "{{ secret('LOOKER_CLIENT_SECRET') }}"
```

The plugin automatically requests an access token on connection, authorizes all subsequent requests using `Authorization: token <access_token>`, and invalidates the token upon completion via `/api/4.0/logout`.

## Tasks

- **`queries.Run`**: Executes an inline Looker model/view query and returns rows or stores results in Kestra storage.
- **`queries.SqlRun`**: Executes a SQL Runner query against a Looker database connection.
- **`looks.Run`**: Runs an existing saved Look by ID, with optional filter overrides.
- **`looks.List`**: Retrieves saved Looks, optionally filtered by folder or title (the title is matched exactly, case-insensitively, with `%` as wildcard).
- **`dashboards.List`**: Retrieves dashboards, optionally filtered by folder or title.
- **`dashboards.Render`**: Asynchronously renders a dashboard or saved Look to PDF, PNG, or JPG and saves the result to Kestra storage.
- **`schedules.RunOnce`**: Triggers execution of an existing or ad-hoc scheduled plan.
- **`projects.Deploy`**: Deploys a LookML project or specific Git branch/ref to production.

## Triggers

- **`looks.Trigger`**: Polling trigger that evaluates a saved Look and initiates flow executions when new rows are detected using namespace KV watermarking.
