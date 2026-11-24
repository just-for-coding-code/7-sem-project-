document.addEventListener("DOMContentLoaded", () => {
  const copyBtn = document.getElementById("copyShaBtn");
  const shaText = document.getElementById("sha256Text");

  if (copyBtn && shaText) {
    copyBtn.addEventListener("click", async () => {
      const value = shaText.textContent.trim();
      try {
        await navigator.clipboard.writeText(value);
        copyBtn.textContent = "Copied";
        setTimeout(() => {
          copyBtn.textContent = "Copy";
        }, 1800);
      } catch {
        copyBtn.textContent = "Select manually";
      }
    });
  }
});
