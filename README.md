# KarooEDSCharge

A Hammerhead Karoo 2 / Karoo 3 extension for live battery voltage monitoring of **Wheeltop EDS XT** electronic shifting systems over Bluetooth Low Energy (BLE).

Designed following the **LightOnKaroo** UI aesthetic with real-time battery status cards and custom data fields.

## Features

- **Live Battery Voltage Monitoring**: Displays individual voltages for Front Derailleur, Rear Derailleur, Shifter Right, and Shifter Left.
- **LightOnKaroo UI Style**: Soft violet background theme, transparent red/turquoise status cards, and right-aligned vector branding logo.
- **Low Power BLE Scanning**: Auto-scan turns off while monitoring a gear. Dedicated 1-minute **Re-Scan** control for manual search.
- **Android 12 Compatible**: Automatic startup permission handling (`BLUETOOTH_SCAN` & `BLUETOOTH_CONNECT`) without needing location services.

## Credits & Acknowledgments

Special thanks to the authors of:
- **[eds-ox](https://git.jeckyll.net/published/personal/eds-ox)** for the reverse-engineering and foundations of Wheeltop BLE communication protocol.
- **[LightOnKaroo](https://github.com)** for UI layout inspiration.

## Changelog

### v0.0.1 (Work in Progress / Initial Build)
- Initial release for Hammerhead Karoo.
- Implemented LightOnKaroo Compose UI layout.
- Added strict BLE filtering for "EDS XT" devices.
- Integrated automated permission setup and Re-Scan timer.

## License

This project is licensed under the **MIT License**.

```text
MIT License

Copyright (c) 2026

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```
