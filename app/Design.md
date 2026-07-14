---
name: Serene Pulse
colors:
  surface: '#051424'
  surface-dim: '#051424'
  surface-bright: '#2c3a4c'
  surface-container-lowest: '#010f1f'
  surface-container-low: '#0d1c2d'
  surface-container: '#122131'
  surface-container-high: '#1c2b3c'
  surface-container-highest: '#273647'
  on-surface: '#d4e4fa'
  on-surface-variant: '#c6c6cd'
  inverse-surface: '#d4e4fa'
  inverse-on-surface: '#233143'
  outline: '#909097'
  outline-variant: '#45464d'
  surface-tint: '#bec6e0'
  primary: '#bec6e0'
  on-primary: '#283044'
  primary-container: '#0f172a'
  on-primary-container: '#798098'
  inverse-primary: '#565e74'
  secondary: '#4edea3'
  on-secondary: '#003824'
  secondary-container: '#00a572'
  on-secondary-container: '#00311f'
  tertiary: '#ffb2b9'
  on-tertiary: '#67001f'
  tertiary-container: '#38000d'
  on-tertiary-container: '#d65569'
  error: '#ffb4ab'
  on-error: '#690005'
  error-container: '#93000a'
  on-error-container: '#ffdad6'
  primary-fixed: '#dae2fd'
  primary-fixed-dim: '#bec6e0'
  on-primary-fixed: '#131b2e'
  on-primary-fixed-variant: '#3f465c'
  secondary-fixed: '#6ffbbe'
  secondary-fixed-dim: '#4edea3'
  on-secondary-fixed: '#002113'
  on-secondary-fixed-variant: '#005236'
  tertiary-fixed: '#ffdadc'
  tertiary-fixed-dim: '#ffb2b9'
  on-tertiary-fixed: '#400010'
  on-tertiary-fixed-variant: '#891933'
  background: '#051424'
  on-background: '#d4e4fa'
  surface-variant: '#273647'
typography:
  display-lg:
    fontFamily: Hanken Grotesk
    fontSize: 48px
    fontWeight: '700'
    lineHeight: 56px
    letterSpacing: -0.02em
  headline-lg:
    fontFamily: Hanken Grotesk
    fontSize: 32px
    fontWeight: '600'
    lineHeight: 40px
    letterSpacing: -0.01em
  headline-lg-mobile:
    fontFamily: Hanken Grotesk
    fontSize: 24px
    fontWeight: '600'
    lineHeight: 32px
  body-md:
    fontFamily: Inter
    fontSize: 16px
    fontWeight: '400'
    lineHeight: 24px
  label-sm:
    fontFamily: Geist
    fontSize: 12px
    fontWeight: '500'
    lineHeight: 16px
    letterSpacing: 0.05em
  stat-number:
    fontFamily: Geist
    fontSize: 20px
    fontWeight: '600'
    lineHeight: 24px
rounded:
  sm: 0.25rem
  DEFAULT: 0.5rem
  md: 0.75rem
  lg: 1rem
  xl: 1.5rem
  full: 9999px
spacing:
  base: 4px
  xs: 8px
  sm: 12px
  md: 16px
  lg: 24px
  xl: 32px
  container-margin: 20px
  stack-gap: 16px
---

## Brand & Style

The design system is centered on **Digital Equanimity**. It targets professionals and students seeking a healthier relationship with their devices. The goal is to transform anxiety-inducing screen time data into actionable, calm insights.

The aesthetic is a sophisticated blend of **Glassmorphism** and **Neumorphic-lite**. It moves away from the flat, clinical look of traditional trackers toward a tactile, atmospheric interface. By using soft depth and translucent layers, the UI feels lightweight and non-intrusive, mirroring the mental clarity the app aims to provide. The emotional response is one of controlled awareness: "I am informed, but not overwhelmed."

## Colors

The palette utilizes a "Deep Logic" hierarchy. The primary foundation is a rich **Slate Navy** (#0F172A), providing a stable, low-strain background for evening use.

- **Primary (Deep Navy):** Used for the base canvas and deep container backgrounds.
- **Secondary (Mint):** Represents "Health" and "Flow." Used for positive progress rings and safe usage zones.
- **Tertiary (Coral):** Represents "Alert" and "Intervention." Used for usage limits, warnings, and high-priority toggle states.
- **Neutral (Slate Grey):** Used for secondary text, inactive states, and subtle borders.

All interactive elements must maintain a high contrast ratio against the deep navy background to ensure accessibility.

## Typography

This design system employs a three-tier typographic strategy to maximize clarity during data consumption.

1. **Hanken Grotesk** is used for headlines and large displays. Its sharp, contemporary geometry provides a premium feel.
2. **Inter** handles all body copy, chosen for its exceptional legibility and neutral tone which reduces cognitive load.
3. **Geist** is used for labels and mono-style data points (like timers and percentages). Its technical precision reinforces the "tracker" nature of the product.

All "Display" and "Headline" styles should use negative letter-spacing to maintain a tight, modern aesthetic on high-resolution mobile screens.

## Layout & Spacing

The layout follows a **Fluid Margin** model optimized for one-handed mobile use.
- **Grid:** An 8-pixel soft grid governs all internal component spacing.
- **Margins:** A standard 20px horizontal safe area is maintained on all screens.
- **Stacking:** Cards are separated by a consistent 16px vertical gap to allow the subtle shadows and "glow" effects space to breathe.
- **Interactive Zones:** All touch targets (buttons, toggles) must maintain a minimum height of 48px, even if the visual element is smaller.

## Elevation & Depth

The design system uses **Layered Translucency** to define hierarchy. Unlike traditional shadow-heavy designs, depth is created through two specific techniques:

1. **Glassmorphic Tiers:** Background containers use a semi-transparent blur (Backdrop Filter: blur 20px) with a 1px inner stroke (linear gradient, white at 10% to white at 0%). This makes cards appear to float above the navy canvas.
2. **Neumorphic Softness:** For interactive buttons, use two extremely soft shadows: a "light" shadow (top-left, 4px blur, white at 5% opacity) and a "dark" shadow (bottom-right, 8px blur, black at 20% opacity). This creates a subtle "pressed" or "extruded" effect without the heavy visual clutter of 2010-era skeuomorphism.

## Shapes

The shape language is consistently **Rounded**.
- **Cards & Containers:** Use `rounded-lg` (16px) to create a friendly, approachable container for data.
- **Progress Rings:** Always use rounded caps for stroke ends to maintain the soft aesthetic.
- **Buttons:** Use a fully "pill-shaped" radius for primary actions to distinguish them from informational cards.
- **Icons:** Should feature a 2px stroke width with rounded joins and caps.

## Components

### Progress Rings
The centerpiece of the UI. Use a thick (12px+) background track in a darker shade of navy. The active track should use a subtle linear gradient of the secondary (Mint) or tertiary (Coral) color. The center of the ring should display the most critical metric in the **stat-number** font style.

### Stat Cards
Rectangular glassmorphic containers. Title labels should be in **label-sm** (all caps). Include a small sparkline or trend indicator (arrow) in the bottom right corner.

### Toggle Switches
The "track" should be a deep, recessed navy. When active, the track glows with the secondary color. The "thumb" should be a white or off-white pill that appears to sit slightly above the track using the neumorphic shadow style.

### Buttons
Primary buttons use a solid gradient of the secondary color. Secondary buttons use the glassmorphic card style (transparent blur) with a prominent 1px border.

### Input Fields
Inputs should feel "sunken" into the interface. Use an inner shadow (inset) to create a slight indentation effect on the dark navy background.