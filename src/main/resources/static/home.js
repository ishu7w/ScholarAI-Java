"use strict";
const menuButton = document.querySelector("[data-home-menu]");
const menu = document.querySelector("#mobile-home-nav");
menuButton?.addEventListener("click", () => {
  menu.hidden = !menu.hidden;
  menuButton.setAttribute("aria-expanded", String(!menu.hidden));
});
menu?.addEventListener("click", (event) => {
  if (event.target.closest("a")) {
    menu.hidden = true;
    menuButton.setAttribute("aria-expanded", "false");
  }
});
const dialog = document.querySelector("#home-copilot");
document.querySelector("[data-open-copilot]")?.addEventListener("click", () => {
  dialog.showModal();
  document.querySelector("#copilot-question").focus();
});
document
  .querySelector("[data-close-copilot]")
  ?.addEventListener("click", () => dialog.close());
const messages = document.querySelector("#copilot-messages");
const form = document.querySelector("#copilot-form");
form?.addEventListener("submit", async (event) => {
  event.preventDefault();
  const question = form.elements.question.value.trim();
  const button = form.querySelector('button[type="submit"]');
  if (!question || button.disabled) return;
  const append = (label, text) => {
    const paragraph = document.createElement("p");
    paragraph.textContent = label + ": " + text;
    messages.append(paragraph);
    paragraph.scrollIntoView({ block: "nearest" });
  };
  append("You", question);
  button.disabled = true;
  try {
    const header = document.querySelector('meta[name="_csrf_header"]').content;
    const token = document.querySelector('meta[name="_csrf"]').content;
    const response = await fetch("/api/chat", {
      method: "POST",
      headers: { "Content-Type": "application/json", [header]: token },
      body: JSON.stringify({ message: question }),
    });
    if (response.status === 401) {
      append(
        "ScholarAI",
        "Sign in to ask about your own profile, matches and applications.",
      );
      const link = document.createElement("a");
      link.href = "/login";
      link.textContent = "Sign in ↗";
      messages.append(link);
    } else if (response.status === 403) {
      append(
        "ScholarAI",
        "Your session changed. Refresh this page and try again.",
      );
    } else {
      const data = await response.json();
      append(
        "ScholarAI",
        data.reply || data.error || "The service is unavailable. Try again.",
      );
    }
    form.elements.question.value = "";
  } catch {
    append(
      "ScholarAI",
      "The service is unavailable. Check your connection and try again.",
    );
  } finally {
    button.disabled = false;
  }
});

document.addEventListener("keydown", (event) => {
  if (event.key === "Escape" && menu && !menu.hidden) {
    menu.hidden = true;
    menuButton.setAttribute("aria-expanded", "false");
    menuButton.focus();
  }
});
