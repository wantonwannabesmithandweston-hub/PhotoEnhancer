import io
import os
import tempfile
from pathlib import Path

from fastapi import FastAPI, UploadFile, File, Form, HTTPException
from fastapi.responses import Response
from PIL import Image, ImageOps, UnidentifiedImageError
from gradio_client import Client, handle_file

app = FastAPI(title="Private Photo Enhancer API")

# Public community Space using GFPGAN face restoration + Real-ESRGAN background upscaling.
# The user's Hugging Face token is read only from Render's secret environment.
HF_SPACE = "Gemini899/GFPGAN-Fix"
MAX_UPLOAD_SIZE = 20 * 1024 * 1024
MAX_IMAGE_PIXELS = 20_000_000
MAX_IMAGE_SIDE = 3000

Image.MAX_IMAGE_PIXELS = MAX_IMAGE_PIXELS


@app.get("/")
def home():
    return {
        "status": "online",
        "service": "Private Photo Enhancer",
        "version": "2.0",
        "provider": "Hugging Face GFPGAN + Real-ESRGAN"
    }


@app.get("/health")
def health():
    return {
        "status": "healthy",
        "provider_configured": bool(os.getenv("HF_TOKEN"))
    }


def _result_file_path(value):
    """Extract a local downloaded path from Gradio's file/image result."""
    if isinstance(value, (str, Path)):
        return str(value)
    if isinstance(value, dict):
        return value.get("path") or value.get("name")
    path = getattr(value, "path", None)
    return str(path) if path else None


@app.post(
    "/enhance",
    response_class=Response,
    responses={
        200: {
            "description": "Enhanced PNG image",
            "content": {"image/png": {"schema": {"type": "string", "format": "binary"}}}
        }
    }
)
async def enhance(
    image: UploadFile = File(...),
    prompt: str = Form(""),
    strength: str = Form("Balanced")
):
    token = os.getenv("HF_TOKEN")
    if not token:
        raise HTTPException(
            status_code=503,
            detail="Hugging Face is not configured on the server."
        )

    if strength not in {"Low", "Balanced", "High", "Ultra"}:
        strength = "Balanced"

    if len(prompt.strip()) > 2000:
        raise HTTPException(status_code=400, detail="Editing instructions are too long.")

    image_bytes = await image.read()
    if not image_bytes or len(image_bytes) > MAX_UPLOAD_SIZE:
        raise HTTPException(
            status_code=400,
            detail="Image is empty or exceeds the 20 MB limit."
        )

    try:
        with Image.open(io.BytesIO(image_bytes)) as opened:
            opened.verify()
        with Image.open(io.BytesIO(image_bytes)) as opened:
            source = ImageOps.exif_transpose(opened).convert("RGB")
            source.load()
            if source.width * source.height > MAX_IMAGE_PIXELS:
                raise HTTPException(status_code=400, detail="Image dimensions are too large.")
            source.thumbnail((MAX_IMAGE_SIDE, MAX_IMAGE_SIDE), Image.Resampling.LANCZOS)
    except HTTPException:
        raise
    except (UnidentifiedImageError, OSError, ValueError, Image.DecompressionBombError):
        raise HTTPException(status_code=400, detail="Please upload a valid photo.")

    # GFPGAN v1.3 is the gentler option; v1.4 restores more aggressively.
    version = "v1.3" if strength == "Low" else "v1.4"

    temp_path = None
    client = None
    try:
        with tempfile.NamedTemporaryFile(suffix=".png", delete=False) as temp_file:
            temp_path = temp_file.name
            source.save(temp_file, format="PNG")

        client = Client(
            HF_SPACE,
            hf_token=token,
            max_workers=1,
            verbose=False,
            analytics_enabled=False
        )
        # The Space's API takes an image, GFPGAN version, and output scale.
        result = client.predict(
            handle_file(temp_path),
            version,
            2,
            api_name="/predict"
        )

        if not isinstance(result, (list, tuple)) or not result:
            raise RuntimeError("The restoration service returned no image.")

        # The Space returns a rendered image plus a downloadable output file.
        candidates = list(result[1:]) + [result[0]]
        output_bytes = None
        for candidate in candidates:
            path = _result_file_path(candidate)
            if path and os.path.isfile(path):
                with Image.open(path) as restored:
                    buffer = io.BytesIO()
                    restored.convert("RGB").save(buffer, format="PNG")
                    output_bytes = buffer.getvalue()
                    break

        if output_bytes is None:
            raise RuntimeError("The restoration service did not return a readable image.")

        return Response(
            content=output_bytes,
            media_type="image/png",
            headers={
                "Cache-Control": "no-store",
                "Content-Disposition": 'inline; filename="enhanced_photo.png"',
                "X-Enhancement-Provider": "Hugging Face GFPGAN + Real-ESRGAN"
            }
        )

    except HTTPException:
        raise
    except Exception as error:
        print("Hugging Face enhancement error:", repr(error))
        raise HTTPException(
            status_code=502,
            detail="Free cloud restoration is temporarily unavailable or its daily queue is busy. Try again later or use Offline Enhance."
        )
    finally:
        if client is not None:
            try:
                client.close()
            except Exception:
                pass
        if temp_path and os.path.exists(temp_path):
            try:
                os.remove(temp_path)
            except OSError:
                pass
