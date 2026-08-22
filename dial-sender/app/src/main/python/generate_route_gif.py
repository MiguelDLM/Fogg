import os
from PIL import Image, ImageDraw, ImageFont

def generate_gif(coords_str, output_path, sport_name, duration_str, distance_str, calories_str):
    # Parse coords_str: "lat,lon;lat,lon;..."
    coords = []
    for item in coords_str.split(";"):
        if not item.strip():
            continue
        try:
            parts = item.split(",")
            lat = float(parts[0])
            lon = float(parts[1])
            coords.append((lat, lon))
        except:
            pass
            
    if not coords:
        # Create a dummy image
        img = Image.new('RGB', (600, 600), color='#0E1116')
        img.save(output_path, format='GIF')
        return True

    # Image size
    W, H = 600, 600
    map_margin = 80
    map_top = 180
    map_bottom = 50
    map_left = map_margin
    map_right = W - map_margin
    map_w = map_right - map_left
    map_h = H - map_bottom - map_top

    # Bounding box of coords
    lats = [c[0] for c in coords]
    lons = [c[1] for c in coords]
    min_lat, max_lat = min(lats), max(lats)
    min_lon, max_lon = min(lons), max(lons)
    
    lat_range = max_lat - min_lat
    lon_range = max_lon - min_lon
    if lat_range == 0: lat_range = 1e-5
    if lon_range == 0: lon_range = 1e-5

    # Scale to preserve aspect ratio
    scale = min(map_w / lon_range, map_h / lat_range)
    # Center the route
    route_w = lon_range * scale
    route_h = lat_range * scale
    offset_x = map_left + (map_w - route_w) / 2
    offset_y = map_top + (map_h - route_h) / 2

    def to_pixel(lat, lon):
        # lon is X, lat is Y (but Y goes down, lat goes up, so invert lat)
        x = offset_x + (lon - min_lon) * scale
        y = offset_y + (max_lat - lat) * scale
        return int(x), int(y)

    # Let's prepare frames
    num_frames = 30
    frames = []
    
    # Try to load Roboto font
    font_path = "/system/fonts/Roboto-Regular.ttf"
    font_bold_path = "/system/fonts/Roboto-Bold.ttf"
    if not os.path.exists(font_path):
        # fallback to default font
        font_title = font_stats = font_label = ImageFont.load_default()
    else:
        font_title = ImageFont.truetype(font_bold_path if os.path.exists(font_bold_path) else font_path, 32)
        font_stats = ImageFont.truetype(font_path, 22)
        font_label = ImageFont.truetype(font_path, 16)

    # Convert all coordinates to pixels
    pixel_coords = [to_pixel(lat, lon) for lat, lon in coords]

    for f in range(num_frames):
        # Create base image
        img = Image.new('RGB', (W, H), color='#0E1116')
        draw = ImageDraw.Draw(img)

        # Draw a subtle grid in map area
        grid_color = '#1A222F'
        for x in range(map_left, map_right + 1, 50):
            draw.line([(x, map_top), (x, H - map_bottom)], fill=grid_color, width=1)
        for y in range(map_top, H - map_bottom + 1, 50):
            draw.line([(map_left, y), (map_right, y)], fill=grid_color, width=1)

        # Draw top card background
        draw.rounded_rectangle([(20, 20), (W - 20, 150)], radius=12, fill='#1A2027', outline='#2D3748', width=1)

        # Draw Header Texts
        draw.text((40, 35), sport_name.upper(), fill='#22D3EE', font=font_title)
        
        # Stats Labels and Values
        draw.text((40, 90), "Distancia", fill='#9AA4B2', font=font_label)
        draw.text((40, 110), distance_str, fill='#FFFFFF', font=font_stats)

        draw.text((220, 90), "Duración", fill='#9AA4B2', font=font_label)
        draw.text((220, 110), duration_str, fill='#FFFFFF', font=font_stats)

        draw.text((420, 90), "Calorías", fill='#9AA4B2', font=font_label)
        draw.text((420, 110), calories_str, fill='#FFFFFF', font=font_stats)

        # Determine how many points to draw in this frame
        percent = (f + 1) / num_frames
        end_idx = int(percent * len(pixel_coords))
        if end_idx < 1:
            end_idx = 1
        
        # Draw the shadow / full route with low opacity
        if len(pixel_coords) > 1:
            draw.line(pixel_coords, fill='#1E2A3B', width=4)

        # Draw the active route (cyan)
        active_coords = pixel_coords[:end_idx]
        if len(active_coords) > 1:
            draw.line(active_coords, fill='#22D3EE', width=5)

        # Draw start point (green circle)
        start_pt = pixel_coords[0]
        draw.ellipse([(start_pt[0] - 6, start_pt[1] - 6), (start_pt[0] + 6, start_pt[1] + 6)], fill='#10B981')

        # Draw end point (red circle)
        end_pt = pixel_coords[-1]
        draw.ellipse([(end_pt[0] - 6, end_pt[1] - 6), (end_pt[0] + 6, end_pt[1] + 6)], fill='#EF4444')

        # Draw current runner position (pulsing marker)
        curr_pt = active_coords[-1]
        # Pulse size varies with frame
        pulse_r = 10 + int(4 * abs((f % 6) - 3) / 3)
        draw.ellipse([(curr_pt[0] - pulse_r, curr_pt[1] - pulse_r), (curr_pt[0] + pulse_r, curr_pt[1] + pulse_r)], outline='#22D3EE', width=1)
        draw.ellipse([(curr_pt[0] - 5, curr_pt[1] - 5), (curr_pt[0] + 5, curr_pt[1] + 5)], fill='#FFFFFF')

        # Watermark/Brand
        draw.text((W - 140, H - 35), "Fogg", fill='#4A5568', font=font_label)

        frames.append(img)

    # Save animated GIF
    frames[0].save(
        output_path,
        save_all=True,
        append_images=frames[1:],
        duration=100, # 100ms per frame
        loop=0
    )
    return True
