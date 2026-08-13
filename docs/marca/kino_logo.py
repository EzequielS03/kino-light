#!/usr/bin/env python3
"""Genera el logo de Kino: monograma K + wordmark, y el path vectorial del icono adaptativo.

La geometria de la K se calcula UNA vez y se usa para los dos destinos (PNG y vector de
Android), asi el icono del lanzador y el del TV no se despegan nunca.
"""
import math
from PIL import Image, ImageDraw, ImageFont

ROJO = (229, 9, 20)          # #E50914, el mismo de la marca
BLANCO = (255, 255, 255)

# --- geometria de la K, en una caja de 100x100 (y hacia abajo) -------------------------------
STEM_X0, STEM_X1 = 12.0, 29.0
TOP, BOT = 8.0, 92.0
DERECHA = 86.0               # borde derecho donde se cortan los brazos
JUNTA_Y = 50.0               # donde los brazos tocan el asta
GROSOR = 17.5                # grosor de cada brazo


def _perp(ax, ay, bx, by, t):
    dx, dy = bx - ax, by - ay
    n = math.hypot(dx, dy)
    return (-dy / n * t, dx / n * t)


def _clip_derecha(poly, x_max):
    """Sutherland-Hodgman contra un solo plano vertical: deja el corte recto."""
    out = []
    for i in range(len(poly)):
        cx, cy = poly[i]
        px, py = poly[i - 1]
        c_in, p_in = cx <= x_max, px <= x_max
        if c_in != p_in:
            t = (x_max - px) / (cx - px)
            out.append((x_max, py + t * (cy - py)))
        if c_in:
            out.append((cx, cy))
    return out


def _brazo(hasta_y):
    """Un brazo desde la junta del asta hasta el borde derecho, cortado recto."""
    ax, ay = STEM_X1 - 6.0, JUNTA_Y          # nace metido en el asta para que suelde
    bx, by = DERECHA + 14.0, hasta_y         # se pasa de largo y despues se corta
    nx, ny = _perp(ax, ay, bx, by, GROSOR / 2)
    quad = [(ax + nx, ay + ny), (bx + nx, by + ny), (bx - nx, by - ny), (ax - nx, ay - ny)]
    return _clip_derecha(quad, DERECHA)


def piezas_k():
    """Las tres piezas de la K en la caja 100x100."""
    asta = [(STEM_X0, TOP), (STEM_X1, TOP), (STEM_X1, BOT), (STEM_X0, BOT)]
    return [asta, _brazo(TOP - 2.0), _brazo(BOT + 2.0)]


def escalar(poly, ox, oy, s):
    return [(ox + x * s, oy + y * s) for x, y in poly]


# --- fondo ------------------------------------------------------------------------------------
def fondo(w, h):
    """Negro con un resplandor rojo abajo a la izquierda, como el logo viejo."""
    img = Image.new("RGB", (w, h), (8, 8, 9))
    px = img.load()
    cx, cy = w * 0.16, h * 0.72
    radio = w * 0.62
    for y in range(h):
        for x in range(w):
            d = math.hypot(x - cx, y - cy) / radio
            k = max(0.0, 1.0 - d) ** 2.0
            px[x, y] = (int(8 + 46 * k), int(8 + 8 * k), int(9 + 12 * k))
    return img


def fuente(px):
    for ruta in ("/System/Library/Fonts/Supplemental/Arial Black.ttf",
                 "/System/Library/Fonts/Supplemental/Arial Bold.ttf"):
        try:
            return ImageFont.truetype(ruta, px)
        except OSError:
            continue
    raise SystemExit("no encontre una fuente bold")


def wordmark(draw, texto, x, y_base, alto_px, tracking):
    """Dibuja el texto letra por letra para poder espaciarlo; devuelve el ancho total."""
    f = fuente(alto_px)
    cursor = x
    for ch in texto:
        draw.text((cursor, y_base), ch, font=f, fill=BLANCO, anchor="ls")
        cursor += draw.textlength(ch, font=f) + tracking
    return cursor - tracking - x


def _mide(draw, texto, alto_px, tracking):
    f = fuente(alto_px)
    return sum(draw.textlength(c, font=f) for c in texto) + tracking * (len(texto) - 1)


def render(w, h):
    img = fondo(w, h)
    draw = ImageDraw.Draw(img)

    # Monograma: caja cuadrada a la izquierda, con aire arriba y abajo.
    lado = h * 0.52
    ox, oy = w * 0.08, (h - lado) / 2
    s = lado / 100.0
    for pieza in piezas_k():
        draw.polygon(escalar(pieza, ox, oy, s), fill=ROJO)

    # Wordmark a la derecha del monograma. El cuerpo se BUSCA para que "KINO" entre justo en
    # el ancho libre: escrito a ojo se salia del lienzo en 480 y sobraba aire en 320.
    tx = ox + lado + w * 0.07
    libre = w * 0.93 - tx
    tracking = w * 0.010
    alto = int(h * 0.34)
    while alto > 8 and _mide(draw, "KINO", alto, tracking) > libre:
        alto -= 1

    base = h * 0.60
    ancho = wordmark(draw, "KINO", tx, base, alto, tracking)

    # Subrayado rojo, como el del logo viejo.
    y0 = base + h * 0.085
    draw.rectangle([tx, y0, tx + ancho, y0 + max(2, h * 0.026)], fill=ROJO)
    return img


def path_android():
    """El mismo monograma como pathData para el icono adaptativo (viewport 108x108)."""
    lado, ox, oy = 62.0, 23.0, 23.0          # centrado en la zona segura de 66dp
    s = lado / 100.0
    trozos = []
    for pieza in piezas_k():
        pts = escalar(pieza, ox, oy, s)
        d = "M" + " L".join(f"{x:.2f},{y:.2f}" for x, y in pts) + " Z"
        trozos.append(d)
    return " ".join(trozos)


if __name__ == "__main__":
    import sys
    destino = sys.argv[1]
    for w, h in ((480, 270), (320, 180)):
        render(w, h).save(f"{destino}/kino_{w}x{h}.png")
    print(path_android())
