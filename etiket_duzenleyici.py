from __future__ import annotations

import os
import subprocess
import sys
import threading
from pathlib import Path
from tkinter import IntVar, StringVar, Tk, filedialog, messagebox
from tkinter import ttk

import fitz
from PIL import Image, ImageChops


APP_NAME = "Trendyol Etiket Düzenleyici"
TARGET_WIDTH = 384
RENDER_DPI = 200
SIDE_MARGIN = 4
LABEL_GAP = 6


def _content_bbox(image: Image.Image) -> tuple[int, int, int, int] | None:
    gray = image.convert("L")
    thresholded = gray.point(lambda pixel: 0 if pixel < 245 else 255)
    ink = ImageChops.invert(thresholded)
    bbox = ink.getbbox()
    if bbox is None:
        return None

    # Boş bir hücredeki birkaç toz pikselini etiket sanma.
    histogram = ink.histogram()
    ink_pixels = sum(histogram[1:])
    if ink_pixels < image.width * image.height * 0.006:
        return None

    left, top, right, bottom = bbox
    return (
        max(0, left - 7),
        max(0, top - 7),
        min(image.width, right + 7),
        min(image.height, bottom + 7),
    )


def extract_labels(pdf_path: Path) -> list[Image.Image]:
    labels: list[Image.Image] = []
    document = fitz.open(pdf_path)
    zoom = RENDER_DPI / 72
    matrix = fitz.Matrix(zoom, zoom)

    try:
        for page in document:
            pixmap = page.get_pixmap(matrix=matrix, alpha=False)
            rendered = Image.frombytes(
                "RGB",
                (pixmap.width, pixmap.height),
                pixmap.samples,
            )

            cell_width = rendered.width / 3
            cell_height = rendered.height / 3

            for row in range(3):
                for column in range(3):
                    x0 = round(column * cell_width)
                    x1 = round((column + 1) * cell_width)
                    y0 = round(row * cell_height)
                    # Trendyol'un küçük 1/13 sayaçları hücrenin en altında.
                    y1 = round(row * cell_height + cell_height * 0.90)
                    cell = rendered.crop((x0, y0, x1, y1))
                    bbox = _content_bbox(cell)
                    if bbox is None:
                        continue

                    cropped = cell.crop(bbox)
                    usable_width = TARGET_WIDTH - 2 * SIDE_MARGIN
                    scale = usable_width / cropped.width
                    resized = cropped.resize(
                        (usable_width, round(cropped.height * scale)),
                        Image.Resampling.LANCZOS,
                    )

                    label = Image.new(
                        "RGB",
                        (TARGET_WIDTH, resized.height + 2 * SIDE_MARGIN),
                        "white",
                    )
                    label.paste(resized, (SIDE_MARGIN, SIDE_MARGIN))
                    labels.append(label)
    finally:
        document.close()

    return labels


def save_batches(
    labels: list[Image.Image],
    output_folder: Path,
    labels_per_image: int,
) -> list[Path]:
    output_folder.mkdir(parents=True, exist_ok=True)
    outputs: list[Path] = []

    for old_file in output_folder.glob("trendyol_etiketleri_*.jpg"):
        old_file.unlink()

    for batch_number, start in enumerate(
        range(0, len(labels), labels_per_image),
        1,
    ):
        batch = labels[start : start + labels_per_image]
        total_height = sum(label.height for label in batch)
        total_height += LABEL_GAP * (len(batch) - 1)
        strip = Image.new("RGB", (TARGET_WIDTH, total_height), "white")

        y = 0
        for label in batch:
            strip.paste(label, (0, y))
            y += label.height + LABEL_GAP

        output_path = output_folder / f"trendyol_etiketleri_{batch_number}.jpg"
        strip.save(
            output_path,
            "JPEG",
            quality=96,
            subsampling=0,
            dpi=(203, 203),
        )
        outputs.append(output_path)

    return outputs


def open_folder(path: Path) -> None:
    if sys.platform == "win32":
        os.startfile(path)  # type: ignore[attr-defined]
    elif sys.platform == "darwin":
        subprocess.run(["open", str(path)], check=False)
    else:
        subprocess.run(["xdg-open", str(path)], check=False)


class EtiketApp:
    def __init__(self) -> None:
        self.root = Tk()
        self.root.title(APP_NAME)
        self.root.geometry("650x455")
        self.root.minsize(620, 430)
        self.root.configure(bg="#f4f6f8")

        self.pdf_path: Path | None = None
        self.output_folder: Path | None = None
        self.file_text = StringVar(value="Henüz bir PDF seçilmedi")
        self.status_text = StringVar(value="PDF seçerek başlayın.")
        self.labels_per_image = IntVar(value=5)
        self._build_ui()

    def _build_ui(self) -> None:
        style = ttk.Style()
        style.theme_use("clam")
        style.configure("Card.TFrame", background="white")
        style.configure(
            "Title.TLabel",
            background="#f4f6f8",
            foreground="#17212b",
            font=("Segoe UI", 20, "bold"),
        )
        style.configure(
            "Subtitle.TLabel",
            background="#f4f6f8",
            foreground="#56616d",
            font=("Segoe UI", 10),
        )
        style.configure(
            "CardTitle.TLabel",
            background="white",
            foreground="#17212b",
            font=("Segoe UI", 11, "bold"),
        )
        style.configure(
            "Body.TLabel",
            background="white",
            foreground="#56616d",
            font=("Segoe UI", 10),
        )
        style.configure(
            "Primary.TButton",
            font=("Segoe UI", 11, "bold"),
            foreground="white",
            background="#f27a1a",
            padding=(18, 11),
        )
        style.map(
            "Primary.TButton",
            background=[("active", "#d96511"), ("disabled", "#c7cbd0")],
        )
        style.configure(
            "Secondary.TButton",
            font=("Segoe UI", 10, "bold"),
            padding=(14, 9),
        )

        container = ttk.Frame(self.root, padding=28)
        container.pack(fill="both", expand=True)

        ttk.Label(
            container,
            text=APP_NAME,
            style="Title.TLabel",
        ).pack(anchor="w")
        ttk.Label(
            container,
            text="Trendyol PDF'sini 58 mm termal yazıcıya uygun JPG şeritlerine dönüştürür.",
            style="Subtitle.TLabel",
        ).pack(anchor="w", pady=(4, 20))

        card = ttk.Frame(container, style="Card.TFrame", padding=22)
        card.pack(fill="both", expand=True)

        ttk.Label(card, text="1. Trendyol PDF'sini seçin", style="CardTitle.TLabel").pack(
            anchor="w"
        )
        file_row = ttk.Frame(card, style="Card.TFrame")
        file_row.pack(fill="x", pady=(10, 22))
        ttk.Label(
            file_row,
            textvariable=self.file_text,
            style="Body.TLabel",
            wraplength=420,
        ).pack(side="left", fill="x", expand=True)
        ttk.Button(
            file_row,
            text="PDF Seç",
            command=self.choose_pdf,
            style="Secondary.TButton",
        ).pack(side="right", padx=(12, 0))

        options = ttk.Frame(card, style="Card.TFrame")
        options.pack(fill="x", pady=(0, 22))
        ttk.Label(
            options,
            text="2. Bir JPG'deki etiket sayısı",
            style="CardTitle.TLabel",
        ).pack(side="left")
        ttk.Spinbox(
            options,
            from_=1,
            to=10,
            width=5,
            textvariable=self.labels_per_image,
            justify="center",
            font=("Segoe UI", 11),
        ).pack(side="right")

        self.convert_button = ttk.Button(
            card,
            text="JPG'leri Hazırla",
            command=self.start_conversion,
            style="Primary.TButton",
            state="disabled",
        )
        self.convert_button.pack(fill="x", pady=(0, 14))

        self.progress = ttk.Progressbar(card, mode="indeterminate")
        self.progress.pack(fill="x", pady=(0, 12))

        footer = ttk.Frame(card, style="Card.TFrame")
        footer.pack(fill="x")
        ttk.Label(
            footer,
            textvariable=self.status_text,
            style="Body.TLabel",
        ).pack(side="left", fill="x", expand=True)
        self.open_button = ttk.Button(
            footer,
            text="Klasörü Aç",
            command=self.open_output,
            style="Secondary.TButton",
            state="disabled",
        )
        self.open_button.pack(side="right", padx=(12, 0))

    def choose_pdf(self) -> None:
        selected = filedialog.askopenfilename(
            title="Trendyol PDF'sini seçin",
            filetypes=[("PDF dosyaları", "*.pdf")],
        )
        if not selected:
            return

        self.pdf_path = Path(selected)
        self.file_text.set(self.pdf_path.name)
        self.status_text.set("PDF hazır. Dönüştür düğmesine basın.")
        self.convert_button.configure(state="normal")
        self.open_button.configure(state="disabled")

    def start_conversion(self) -> None:
        if self.pdf_path is None:
            return
        try:
            per_image = int(self.labels_per_image.get())
            if not 1 <= per_image <= 10:
                raise ValueError
        except (ValueError, TypeError):
            messagebox.showwarning(APP_NAME, "Etiket sayısı 1 ile 10 arasında olmalı.")
            return

        self.convert_button.configure(state="disabled")
        self.open_button.configure(state="disabled")
        self.progress.start(12)
        self.status_text.set("Etiketler hazırlanıyor...")
        threading.Thread(
            target=self._convert_worker,
            args=(per_image,),
            daemon=True,
        ).start()

    def _convert_worker(self, per_image: int) -> None:
        assert self.pdf_path is not None
        try:
            labels = extract_labels(self.pdf_path)
            if not labels:
                raise RuntimeError("PDF içinde etiket bulunamadı.")

            folder = self.pdf_path.parent / f"{self.pdf_path.stem}_jpg_etiketler"
            outputs = save_batches(labels, folder, per_image)
            self.output_folder = folder
            self.root.after(
                0,
                lambda: self._conversion_finished(len(labels), len(outputs)),
            )
        except Exception as exc:
            self.root.after(0, lambda: self._conversion_failed(str(exc)))

    def _conversion_finished(self, label_count: int, file_count: int) -> None:
        self.progress.stop()
        self.convert_button.configure(state="normal")
        self.open_button.configure(state="normal")
        self.status_text.set(
            f"Tamamlandı: {label_count} etiket, {file_count} JPG oluşturuldu."
        )
        messagebox.showinfo(
            APP_NAME,
            f"{label_count} etiket başarıyla hazırlandı.\n\n"
            f"Oluşturulan JPG sayısı: {file_count}",
        )

    def _conversion_failed(self, error: str) -> None:
        self.progress.stop()
        self.convert_button.configure(state="normal")
        self.status_text.set("İşlem tamamlanamadı.")
        messagebox.showerror(APP_NAME, f"PDF dönüştürülemedi:\n\n{error}")

    def open_output(self) -> None:
        if self.output_folder is not None:
            open_folder(self.output_folder)

    def run(self) -> None:
        self.root.mainloop()


if __name__ == "__main__":
    EtiketApp().run()
