import { createIconResponse } from "@/lib/icon-response";

export const dynamic = "force-static";

export function GET() {
  return createIconResponse({ maskable: true, size: 512 });
}
