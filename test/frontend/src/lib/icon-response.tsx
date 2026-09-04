import { ImageResponse } from "next/og";

interface IconOptions {
  maskable?: boolean;
  size: number;
}

export function createIconResponse({ maskable = false, size }: IconOptions) {
  const inset = maskable ? Math.round(size * 0.18) : Math.round(size * 0.08);
  const radius = maskable ? Math.round(size * 0.22) : Math.round(size * 0.18);

  return new ImageResponse(
    (
      <div
        style={{
          alignItems: "center",
          background: "#101a16",
          display: "flex",
          height: "100%",
          justifyContent: "center",
          width: "100%",
        }}
      >
        <div
          style={{
            alignItems: "center",
            background: "#c8ff37",
            borderRadius: radius,
            color: "#101a16",
            display: "flex",
            fontFamily: "Arial, sans-serif",
            fontSize: Math.round(size * 0.48),
            fontWeight: 900,
            height: size - inset * 2,
            justifyContent: "center",
            lineHeight: 1,
            width: size - inset * 2,
          }}
        >
          +
        </div>
      </div>
    ),
    { height: size, width: size },
  );
}
