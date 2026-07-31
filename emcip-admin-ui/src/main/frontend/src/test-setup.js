import '@testing-library/jest-dom'

// Polyfill Blob.text() for jsdom
if (typeof Blob !== 'undefined' && !Blob.prototype.text) {
  Blob.prototype.text = function() {
    return new Promise((resolve, reject) => {
      const reader = new FileReader()
      reader.onload = () => resolve(reader.result)
      reader.onerror = () => reject(reader.error)
      reader.readAsText(this)
    })
  }
}
