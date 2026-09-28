from PIL import Image
import os
SRC = r"C:\Users\Administrator\Pictures\loading.png"
RES = r"c:\Users\Administrator\Downloads\DaisyDiverLoder\app\src\main\res"
DENS = {"mipmap-mdpi":48,"mipmap-hdpi":72,"mipmap-xhdpi":96,"mipmap-xxhdpi":144,"mipmap-xxxhdpi":192}
XDPI = {"mipmap-mdpi":432,"mipmap-hdpi":432,"mipmap-xhdpi":432,"mipmap-xxhdpi":432,"mipmap-xxxhdpi":432}
src = Image.open(SRC).convert("RGBA")
print("SRC", src.size, src.mode)
for folder, px in DENS.items():
    d = os.path.join(RES, folder)
    os.makedirs(d, exist_ok=True)

    for f in ["ic_launcher.webp","ic_launcher_round.webp"]:
        p=os.path.join(d,f)
        if os.path.exists(p): os.remove(p); print("REMOVED",p)
    legacy = src.resize((px,px), Image.LANCZOS)
    legacy.save(os.path.join(d,"ic_launcher.png"))
    legacy.save(os.path.join(d,"ic_launcher_round.png"))
    print("WROTE",folder,px)

canvas = 432
inner = int(432*72/108)
os.makedirs(os.path.join(RES,"drawable-nodpi"), exist_ok=True)
fg = Image.new("RGBA",(canvas,canvas),(0,0,0,0))
art = src.resize((inner,inner), Image.LANCZOS)
fg.paste(art, ((canvas-inner)//2,(canvas-inner)//2), art)
fg.save(os.path.join(RES,"drawable-nodpi","app_logo_foreground.png"))
print("FG_DONE", fg.size)
