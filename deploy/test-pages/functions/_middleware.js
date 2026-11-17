// Every request that reaches a Function on the test deployment passes this
// gate first. _routes.json (tools/test-access/stage.mjs) lets only the
// release's static files, each by its exact path, skip it; the code is public
// anyway.
import {
  authorizedLabel,
  notConfiguredResponse,
  unauthorizedResponse,
} from "../lib/access_gate.mjs";

export async function onRequest({ request, env, next }) {
  const store = env.TEST_ACCESS;
  if (!store) {
    return notConfiguredResponse();
  }

  const label = await authorizedLabel(
    request.headers.get("authorization"),
    (key) => store.get(key),
  );

  if (label === null) {
    return unauthorizedResponse();
  }

  return next();
}
