from flask import Flask, request, render_template
from PIL import Image
import io, base64

app = Flask(__name__)

@app.route("/")
def index():
    return render_template("index.html")

@app.route("/crop_image", methods=["POST"])
def crop_image():
    # Get the uploaded image
    image = request.files.get("image")

    # Open the image using PIL
    image = Image.open(image)

    # Get the image size
    width, height = image.size

    # Define the box that represents the area of the image to keep
    box = (0, 0, width, height - 17)

    # Crop the image to the defined box
    cropped_image = image.crop(box)

    # Create a memory stream for the cropped image
    mem_file = io.BytesIO()
    cropped_image.save(mem_file, "JPEG")
    mem_file.seek(0)

    # Encode the cropped image as a base64 string
    encoded_image = base64.b64encode(mem_file.read()).decode("utf-8")

    # Return the cropped image to the template as a response
    return render_template("index.html", image=encoded_image)

if __name__ == "__main__":
    app.run()
