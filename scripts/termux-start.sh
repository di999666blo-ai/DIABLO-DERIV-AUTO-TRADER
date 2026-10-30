#!/data/data/com.termux/files/usr/bin/bash
set -e
cd "$HOME/DIABLO-DERIV-AUTO-TRADER"
command -v node >/dev/null 2>&1 || { echo "Node.js is required"; exit 1; }
command -v tmux >/dev/null 2>&1 || { echo "Install tmux first"; exit 1; }
if tmux has-session -t diablo-deriv 2>/dev/null; then
  echo "DIABLO DERIV is already running"
  exit 0
fi
termux-wake-lock 2>/dev/null || true
tmux new-session -d -s diablo-deriv "cd \"$HOME/DIABLO-DERIV-AUTO-TRADER\" && node --env-file=.env src/server.js >> \"$HOME/diablo-deriv.log\" 2>&1"
echo "DIABLO DERIV service started in tmux session diablo-deriv"
