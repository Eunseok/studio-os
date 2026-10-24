import type { Metadata } from "next";
import "./globals.css";
export const metadata: Metadata = {
  title: "Studio OS · 개발 스튜디오",
  description: "로컬 AI 개발 스튜디오 운영 콘솔",
};
export default function RootLayout({
  children,
}: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="ko">
      <body>{children}</body>
    </html>
  );
}
