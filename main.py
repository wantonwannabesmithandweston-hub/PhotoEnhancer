
import os
import io
import re
import base64

from fastapi import FastAPI, UploadFile, File, Form, HTTPException
from fastapi.responses import Response
from openai import OpenAI
from PIL import Image, ImageOps, UnidentifiedImageError

app = FastAPI(title="Private Photo Enhancer API")

MODEL = "gpt-image-2.5-sunburst"
MAX_UPLOAD_SIZE = 20 * 1024 * 1024
MAX_IMAGE_PIXELS = 40_000_000

Image.MAX_IMAGE_PIXELS = MAX_IMAGE_PIXELS


@app.get("/")
def home():
    return {
        "status": "online",
        "service": "Private Photo Enhancer",
        "version": "1.2"
    }


@app.get("/health")
def health():
    return {"status": "healthy"}


@app.post(
    "/enhance",
    response_class=Response,
    responses={
        200: {
            "description": "Enhanced PNG image",
            "content": {
                "image/png": {
                    "schema": {
                        "type": "string",
                        "format": "binary"
                    }
                }
            }
        }
    }
)
async def enhance(
    image: UploadFile = File(...),
    prompt: str = Form(""),
    strength: str = Form("Balanced")
):
    api_key = os.getenv("OPENAI_API_KEY")

    if not api_key:
        raise HTTPException(
            status_code=500,
            detail="OpenAI API key is not configured."
        )

    quality_settings = {
        "Low": "low",
        "Balanced": "medium",
        "High": "high",
        "Ultra": "xhigh"
    }

    if strength not in quality_settings:
        strength = "Balanced"

    prompt = prompt.strip()

    if len(prompt) > 2000:
        raise HTTPException(
            status_code=400,
            detail="Editing instructions are too long."
        )

    unsafe_terms = re.compile(
        r"\b(undress|remove clothing|remove clothes|"
        r"make nude|make naked|topless|"
        r"see through clothes|strip naked)\b",
        re.IGNORECASE
    )

    if unsafe_terms.search(prompt):
        raise HTTPException(
            status_code=400,
            detail="This type of clothing-removal edit is not supported."
        )

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
            source = ImageOps.exif_transpose(opened)
            source.load()

            if source.width * source.height > MAX_IMAGE_PIXELS:
                raise HTTPException(
                    status_code=400,
                    detail="Image dimensions are too large."
                )

            source = source.convert("RGB")

        buffer = io.BytesIO()
        source.save(buffer, format="PNG")
        buffer.seek(0)
        buffer.name = "photo.png"

    except HTTPException:
        raise

    except Exception:
        raise HTTPException(
            status_code=400,
            detail="Please upload a valid photo."
        )

    strength_instructions = {
        "Low": (
            "Apply gentle photographic restoration. "
            "Preserve the original appearance with subtle cleanup."
        ),

        "Balanced": (
            "Apply thorough but natural photographic restoration. "
            "Improve clarity, reduce noise, correct color casts, "
            "and recover visible fine details without overprocessing."
        ),

        "High": (
            "Apply advanced photographic restoration. "
            "Improve fine textures, hair definition, clothing details, "
            "edge clarity, noise reduction and color accuracy. "
            "Keep the result realistic and faithful to the source."
        ),

        "Ultra": (
            "Apply the strongest careful photographic restoration "
            "available. Prioritize recovering authentic visible detail, "
            "natural facial texture, hair definition, clothing texture, "
            "and fine image structure. Reduce blur, noise and compression "
            "artifacts as much as reasonably possible. "
            "Correct color casts and improve tonal balance. "
            "Avoid excessive sharpening, artificial skin, halos, "
            "oversaturated colors and invented details."
        )
    }

    instructions = """
You are a professional photographic restoration system.

Your task is to restore and enhance the supplied photograph,
not to redesign it or create a different photograph.

IDENTITY PRESERVATION:
Preserve the exact identity and recognizable appearance of
every person in the original photograph.

Do not change facial proportions, eye shape, nose, mouth,
jawline, expression, hairstyle, age or other identifying features.

Do not beautify, reshape or artificially reconstruct faces.

FACIAL DETAIL:
Improve the clarity of naturally visible facial features.
Preserve realistic skin texture and natural imperfections.
Avoid plastic-looking skin, excessive smoothing and
artificially painted facial details.

IMAGE SHARPNESS:
Improve genuine visible edges and fine textures.
Reduce motion blur and softness where recoverable.
Avoid excessive sharpening, ringing and unnatural outlines.

COLOR RESTORATION:
Correct unwanted red, orange, yellow or other color casts.
Restore natural-looking skin tones.
Maintain believable clothing and background colors.
Avoid excessive saturation and artificial color grading.

LIGHTING:
Improve exposure and tonal balance.
Recover highlight detail where information is available.
Improve shadow visibility without making the photograph flat.
Avoid excessive brightness and blown-out whites.

NOISE REDUCTION:
Reduce grain, digital noise and compression artifacts.
Preserve useful texture rather than smoothing everything.

DETAIL PRESERVATION:
Improve naturally visible hair, clothing, objects and
background textures without changing their original shapes.

SCENE PRESERVATION:
Keep the original people, objects, clothing, pose,
background, composition and camera perspective unchanged.

IMPORTANT:
Do not add objects or remove objects.
Do not invent information that cannot be recovered.
Do not change the person's identity.
Do not turn the photograph into a painting, illustration,
cartoon or synthetic-looking image.

The final result must look like a naturally restored
photograph of the same original scene.
"""

    instructions += (
        "\n\nSELECTED ENHANCEMENT STRENGTH:\n"
        + strength_instructions[strength]
    )

    if prompt:
        instructions += (
            "\n\nADDITIONAL USER INSTRUCTIONS:\n"
            + prompt
            + "\nFollow these instructions while preserving "
              "the original photograph and people's identities."
        )

    try:
        client = OpenAI(api_key=api_key)

        result = client.images.edit(
            model=MODEL,
            image=buffer,
            prompt=instructions,
            quality=quality_settings[strength],
            output_format="png"
        )

        if not result.data or not result.data[0].b64_json:
            raise RuntimeError("No enhanced image was returned.")

        output_bytes = base64.b64decode(
            result.data[0].b64_json,
            validate=True
        )

        if not output_bytes:
            raise RuntimeError("The returned image was empty.")

        return Response(
            content=output_bytes,
            media_type="image/png",
            headers={
                "Cache-Control": "no-store",
                "Content-Disposition": (
                    'inline; filename="enhanced_photo.png"'
                )
            }
        )

    except Exception as error:
        print("Enhancement error:", str(error))

        raise HTTPException(
            status_code=502,
            detail="AI enhancement failed. Please try again."
        )
