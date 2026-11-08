# Pixel Buds Pro 2 turntable

How `android/app/src/main/res/raw/pixel_buds_pro_2_hazel_turntable.webp` (the case card's
animation) was made. The model and lighting map are Google's, from the Pixel Buds app
(`assets/webcontent/largo/largo.glb` and `hdr_map_largo.hdr`); they aren't kept in this repo.

1. Put `largo.glb` and `hdr_map_largo.hdr` next to `index.html` and `server.py`.
2. `python3 server.py 8765`, then open
   `http://localhost:8765/index.html?frames=120&w=720&zoom=0.8&tilt=10&anim=Tilted_Buds_Zoom_In`.
   The page (three.js 0.160 from jsDelivr) keeps only the two buds with medium ear tips, poses them
   with the model's `Tilted_Buds_Zoom_In` animation, colours them like Google's viewer does
   (`largoBudMaterialMap`; Hazel is colour code 10: caps 0x565D5A without texture, top caps
   0x565D5A with 0x646464 emission at 0.6), turns them 360 degrees and POSTs each frame to
   `frames/NNN.png`.
3. `img2webp -loop 0 -lossy -q 78 -m 6 -d 33 frames/[0-9][0-9][0-9].png -o pro2_hazel_turntable.webp`

Other colours: change `HAZEL` in `index.html` to the colour code's entry from Google's table.
`?frames=1&tag=x` renders one test frame; `?debug=1` colours the left bud red and the right blue.
Hide parts by material, not node: the left bud hangs under the case's mesh.
