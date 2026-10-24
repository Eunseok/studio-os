import { NextRequest, NextResponse } from "next/server";
// Preserve the local-only boundary before the reverse proxy rewrites Host.
export function proxy(request: NextRequest) {
  const host = request.headers.get("host")?.split(":")[0];
  if (host !== "localhost" && host !== "127.0.0.1")
    return new NextResponse("Local Studio host required", { status: 403 });
  return NextResponse.next();
}
export const config = {
  matcher: ["/((?!_next/static|_next/image|favicon.ico).*)"],
};
