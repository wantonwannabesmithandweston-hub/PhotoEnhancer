
import os
import io
import re
import base64

from fastapi import FastAPI, UploadFile, File, Form, HTTPException
from fastapi.responses import Response
from openai import OpenAI
from PIL import Image, ImageOps

app = FastAPI(title="Private Photo Enhancer API")

MODEL = "gpt-image-2.5-sunburst"
MAX_UPLOAD_SIZE = 20 * 1024 * 1024

@app.get("/")
def home():
    return {
        "status": "online",
        "service": "Private Photo Enhancer",
        "version": "1.0"
    }

@app.get("/health")
def health():
    return {"status": "healthy"}

@app.post("/enhance")
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

    if strength not in ["Low", "Balanced", "High", "Ultra"]:
        strength = "Balanced"

    prompt = prompt.strip()

    if len(prompt) > 2000:
        raise HTTPException(400, "Editing instructions are too long.")

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
        source = Image.open(io.BytesIO(image_bytes))
        source = ImageOps.exif_transpose(source)
        source = source.convert("RGB")

        buffer = io.BytesIO()
        source.save(buffer, format="PNG")
        buffer.seek(0)
        buffer.name = "photo.png"

    except Exception:
        raise HTTPException(
            status_code=400,
            detail="Please upload a valid photo."
        )

    strength_instructions = {
        "Low": "Apply subtle, natural improvements.",
        "Balanced": "Apply strong but natural-looking improvements.",
        "High": "Apply advanced enhancement with detailed restoration.",
        "Ultra": "Apply maximum careful restoration and detail enhancement."
    }

    instructions = (
        "Edit the supplied photograph. Preserve the person's identity, "
        "facial features, clothing, pose and natural appearance. "
        "Do not invent facial details or change the person's identity. "
        "Keep people appropriately clothed. Do not create nude images "
        "or remove clothing. Preserve realistic photographic quality. "
        + strength_instructions[strength]
    )

    if prompt:
        instructions += "\nAdditional requested edits: " + prompt

    try:
        client = OpenAI(api_key=api_key)

        result = client.images.edit(
            model=MODEL,
            image=buffer,
            prompt=instructions,
            quality="high"
        )

        image_data = result.data[0].b64_json
        output_bytes = base64.b64decode(image_data)

        return Response(
            content=output_bytes,
            media_type="image/png",
            headers={
                "Cache-Control": "no-store"
            }
        )

    except Exception as error:
        print("Enhancement error:", str(error))
        raise HTTPException(
            status_code=502,
            detail="AI enhancement failed. Please try again."
        )
