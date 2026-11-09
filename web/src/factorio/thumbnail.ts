/** Cadre une capture FBE dans une image 1200x630 sur le fond anthracite du site (aperçus de liens). */
export async function frameThumbnail(picture: Blob): Promise<Blob | null> {
  const bitmap = await createImageBitmap(picture);
  const w = 1200, h = 630, margin = 24;
  const canvas = document.createElement("canvas");
  canvas.width = w;
  canvas.height = h;
  const ctx = canvas.getContext("2d");
  if (!ctx) return null;
  ctx.fillStyle = "#1e1f22";
  ctx.fillRect(0, 0, w, h);
  const scale = Math.min((w - margin * 2) / bitmap.width, (h - margin * 2) / bitmap.height);
  const dw = bitmap.width * scale, dh = bitmap.height * scale;
  ctx.imageSmoothingQuality = "high";
  ctx.drawImage(bitmap, (w - dw) / 2, (h - dh) / 2, dw, dh);
  bitmap.close();
  return new Promise((resolve) => canvas.toBlob((b) => resolve(b), "image/png"));
}

/** Résolution à demander à FBE (1 = 32 px par tuile) pour que la capture tienne dans 1200x630. */
export function pictureResolution(widthTiles: number, heightTiles: number): number {
  const r = Math.min(1200 / (32 * Math.max(1, widthTiles)), 630 / (32 * Math.max(1, heightTiles)));
  return Math.max(0.03, Math.min(1, r));
}
