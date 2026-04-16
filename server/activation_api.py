"""
VistaCore Device Activation API

Simple Flask server for managing device activation. Deploy to your VPS
alongside the existing pair/filter services.

Endpoints:
    GET  /api/device/status?device_id=xxx   → {"active": true/false, "device_id": "xxx"}
    POST /api/device/register               → registers a device (body: {device_id, device_name, app_version})
    POST /api/device/activate               → activate a device (body: {device_id})
    POST /api/device/deactivate             → lock a device (body: {device_id})
    GET  /api/devices                       → list all registered devices

Data is stored in a JSON file on disk (no database needed).

Usage:
    pip install flask
    python activation_api.py                 # runs on port 5050
    ADMIN_KEY=your-secret python activation_api.py

Admin endpoints (activate/deactivate/list) require the header:
    X-Admin-Key: <ADMIN_KEY>
"""

import json
import os
from datetime import datetime
from flask import Flask, request, jsonify
from threading import Lock

app = Flask(__name__)

DATA_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "devices.json")
ADMIN_KEY = os.environ.get("ADMIN_KEY", "vistacore-admin")
file_lock = Lock()


def load_devices():
    if not os.path.exists(DATA_FILE):
        return {}
    with open(DATA_FILE, "r") as f:
        return json.load(f)


def save_devices(devices):
    with file_lock:
        with open(DATA_FILE, "w") as f:
            json.dump(devices, f, indent=2)


def require_admin():
    key = request.headers.get("X-Admin-Key", "")
    if key != ADMIN_KEY:
        return jsonify({"error": "unauthorized"}), 401
    return None


# ─────────────────── Public endpoints ───────────────────


@app.route("/api/device/status", methods=["GET"])
def device_status():
    device_id = request.args.get("device_id", "").strip()
    if not device_id:
        return jsonify({"error": "device_id required"}), 400

    devices = load_devices()
    device = devices.get(device_id)

    if device is None:
        # Unknown device — default to active (auto-register on first check)
        devices[device_id] = {
            "active": True,
            "device_name": "unknown",
            "app_version": "unknown",
            "registered_at": datetime.utcnow().isoformat(),
            "last_seen": datetime.utcnow().isoformat(),
        }
        save_devices(devices)
        return jsonify({"active": True, "device_id": device_id})

    # Update last_seen timestamp
    device["last_seen"] = datetime.utcnow().isoformat()
    save_devices(devices)

    return jsonify({"active": device.get("active", True), "device_id": device_id})


@app.route("/api/device/register", methods=["POST"])
def device_register():
    data = request.get_json(silent=True) or {}
    device_id = data.get("device_id", "").strip()
    if not device_id:
        return jsonify({"error": "device_id required"}), 400

    devices = load_devices()
    now = datetime.utcnow().isoformat()

    if device_id in devices:
        # Update existing device info
        devices[device_id]["device_name"] = data.get("device_name", devices[device_id].get("device_name", "unknown"))
        devices[device_id]["app_version"] = data.get("app_version", devices[device_id].get("app_version", "unknown"))
        devices[device_id]["last_seen"] = now
    else:
        # New device — active by default
        devices[device_id] = {
            "active": True,
            "device_name": data.get("device_name", "unknown"),
            "app_version": data.get("app_version", "unknown"),
            "registered_at": now,
            "last_seen": now,
        }

    save_devices(devices)
    return jsonify({"ok": True, "device_id": device_id})


# ─────────────────── Admin endpoints ───────────────────


@app.route("/api/device/activate", methods=["POST"])
def device_activate():
    auth_err = require_admin()
    if auth_err:
        return auth_err

    data = request.get_json(silent=True) or {}
    device_id = data.get("device_id", "").strip()
    if not device_id:
        return jsonify({"error": "device_id required"}), 400

    devices = load_devices()
    if device_id not in devices:
        return jsonify({"error": "device not found"}), 404

    devices[device_id]["active"] = True
    save_devices(devices)
    return jsonify({"ok": True, "device_id": device_id, "active": True})


@app.route("/api/device/deactivate", methods=["POST"])
def device_deactivate():
    auth_err = require_admin()
    if auth_err:
        return auth_err

    data = request.get_json(silent=True) or {}
    device_id = data.get("device_id", "").strip()
    if not device_id:
        return jsonify({"error": "device_id required"}), 400

    devices = load_devices()
    if device_id not in devices:
        return jsonify({"error": "device not found"}), 404

    devices[device_id]["active"] = False
    save_devices(devices)
    return jsonify({"ok": True, "device_id": device_id, "active": False})


@app.route("/api/devices", methods=["GET"])
def list_devices():
    auth_err = require_admin()
    if auth_err:
        return auth_err

    devices = load_devices()
    result = []
    for device_id, info in devices.items():
        result.append({
            "device_id": device_id,
            "active": info.get("active", True),
            "device_name": info.get("device_name", "unknown"),
            "app_version": info.get("app_version", "unknown"),
            "registered_at": info.get("registered_at", ""),
            "last_seen": info.get("last_seen", ""),
        })
    return jsonify(result)


if __name__ == "__main__":
    print(f"VistaCore Activation API starting on port 5050")
    print(f"Admin key: {ADMIN_KEY}")
    app.run(host="0.0.0.0", port=5050)
