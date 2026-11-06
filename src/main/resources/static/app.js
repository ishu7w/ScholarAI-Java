// Progressive enhancement: Java renders each page and persists every submitted form.
// The browser only handles the live funding estimate and filtering already-loaded rows.
"use strict";
const aidForm = document.querySelector("#aid-form");
if (aidForm) {
  const update = () => {
    const amount = (name) =>
      Math.max(0, Number(aidForm.elements[name].value) || 0);
    const gap = Math.max(
      0,
      amount("cost") - amount("confirmed") - amount("contribution"),
    );
    document.querySelector("#gap").textContent = new Intl.NumberFormat(
      undefined,
      {
        style: "currency",
        currency: aidForm.elements.currency.value,
      },
    ).format(gap);
  };
  aidForm.addEventListener("input", update);
}
for (const input of document.querySelectorAll("[data-filter]")) {
  input.addEventListener("input", () => {
    const container = document.getElementById(input.dataset.filter);
    const rows = container.querySelectorAll("tbody tr, .filter-item");
    const query = input.value.trim().toLowerCase();
    let shown = 0;
    for (const row of rows) {
      row.hidden = !row.textContent.toLowerCase().includes(query);
      if (!row.hidden) shown++;
    }
    let status = input.closest("label").nextElementSibling;
    if (!status?.classList.contains("filter-status")) {
      status = document.createElement("p");
      status.className = "filter-status";
      status.setAttribute("role", "status");
      input.closest("label").after(status);
    }
    status.textContent =
      shown === 0
        ? "No matching results. Try another search."
        : shown + " matching results.";
  });
}

const workspace = document.querySelector(".workspace-nav");
workspace?.classList.add("nav-enhanced");
const navigationButton = document.querySelector(".mobile-navigation");
navigationButton?.addEventListener("click", () => {
  const open = workspace.classList.toggle("nav-open");
  navigationButton.setAttribute("aria-expanded", String(open));
});
for (const link of document.querySelectorAll(".workspace-nav nav a")) {
  if (new URL(link.href).pathname === location.pathname)
    link.setAttribute("aria-current", "page");
}
