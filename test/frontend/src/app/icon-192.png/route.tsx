import { createIconResponse } from "@/lib/icon-response";

export const dynamic = "force-static";

export function GET() {
  return createIconResponse({ size: 192 });
}
