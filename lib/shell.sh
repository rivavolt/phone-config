#!/usr/bin/env bash
# Make zsh the login shell. Termux's chsh writes a symlink at ~/.termux/shell
# pointing at the chosen binary — there's no /etc/passwd on Termux.

shell_set_zsh() {
  command -v zsh >/dev/null || die "zsh not installed; packages step must run first"

  local zsh_path link
  zsh_path=$(command -v zsh)
  link="$HOME/.termux/shell"

  if [ -L "$link" ] && [ "$(readlink "$link")" = "$zsh_path" ]; then
    skip "login shell already zsh"
    return 0
  fi

  do_ "chsh -> $zsh_path"
  chsh -s zsh
  ok "login shell set to zsh"
}
