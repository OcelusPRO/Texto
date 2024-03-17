function get_url_extension(url) {
    return url.split(/[#?]/)[0].split('.').pop().trim();
}

document.getElementById("codeZone").classList.add(get_url_extension(document.URL))


const rawButton = document.getElementById("menu-raw")
rawButton.addEventListener('click', () => {
    window.open(`/raw/${window.location.pathname}`);
})


const copyButton = document.getElementById("menu-copy")
const code = document.getElementById("codeZone")
copyButton.addEventListener("click", () => {
    navigator.clipboard.writeText(code.innerText).then(r => {
        alert("Le contenu a été copier dans votre press papier.");
    });
})


const duplicateButton = document.getElementById("menu-duplicate")
const newButton = document.getElementById("menu-new")

const deleteButton = document.getElementById("menu-delete")

if (deleteButton) {
    deleteButton.addEventListener("click", () => {
        window.location.replace(`${window.location.href}/delete`);
    })
}


const numberList = document.getElementById("number-list")
const numberDiv = '<div class="w-4 text-gray-400">{key}</div>'

function showNumList(size) {
    let result = ''
    for (let i = 1; i < size + 1; i++) {
        result += `\n${numberDiv.replace('{key}', i)}`
    }
    numberList.innerHTML = result
}

showNumList(code.innerText.split('\n').length)

const socialMedia = document.getElementById("socialMedia")
const mediaDiv = `
<a href="{0}" target="_blank">
    <div class="h-8 w-8 text-center tooltip">
        <i class="{1}"></i>
        <div class="bottom">
            <p>{0}</p>
        </div>
    </div>
</a>
`

function showSocialMedia() {
    let data = JSON.parse(socialMedia.dataset.social)
    if (!data) data = []
    let result = ''
    for (let dataKey of data) {
        const r = mediaDiv
            .replaceAll('{0}', dataKey.url)
            .replaceAll('{1}', dataKey.icon)
        result += `\n${r}`
    }
    socialMedia.innerHTML = result
}
showSocialMedia()

hljs.highlightAll()