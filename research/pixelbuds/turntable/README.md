# Pixel Buds Pro 2 turntable

How `android/app/src/main/res/raw/pixel_buds_pro_2_hazel_card.webp` (the case card's animation:
buds and case turning side by side) was made, with `pair.html`. `index.html` renders one part at a
time (the buds, or `?part=case`) and is handy for checking poses and colours. The model and lighting map are Google's, from the Pixel Buds app
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

For the card: `http://localhost:8765/pair.html?frames=120&w=960&tilt=10` loads the model twice,
the buds posed with `Tilted_Buds_Zoom_In` and the open case at rest (its shell, `case_bottom_outer`
and `outside_lid`, in the buds' colour: Google's table has no case colours), and places them with an
orthographic camera so their centres sit at 28% and 71% of the width, where the card puts the
batteries. 960 x 540, 120 frames, 648 KB as WebP.

Other colours: change `HAZEL` in `index.html` to the colour code's entry from Google's table.
`?frames=1&tag=x` renders one test frame; `?debug=1` colours the left bud red and the right blue.
Hide parts by material, not node: the left bud hangs under the case's mesh.
