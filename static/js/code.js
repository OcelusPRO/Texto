function get_url_extension(url) {
    return url.split(/[#?]/)[0].split('.').pop().trim();
}

document.getElementById("codeZone").classList.add(get_url_extension(document.URL))


const rawButton = document.getElementById("menu-raw")
if (rawButton){
    rawButton.addEventListener('click', () => {
        window.open(`/raw${window.location.pathname}`);
    })
}

const gitButton = document.getElementById("menu-git")
if (gitButton){
    gitButton.addEventListener('click', () => {
        window.open(`https://github.com/OcelusPRO/Texto`);
    })
}

const copyButton = document.getElementById("menu-copy")
const code = document.getElementById("codeZone")
if (copyButton){
    copyButton.addEventListener("click", () => {
        navigator.clipboard.writeText(code.innerText).then(r => {
            alert("Le contenu a été copier dans votre press papier.");
        });
    })
}


const duplicateButton = document.getElementById("menu-duplicate")
if (duplicateButton){
    duplicateButton.addEventListener("click", () => {
        const protocol = window.location.protocol
        const host = window.location.host
        const path = window.location.pathname.replace('/', '')
        window.location.href = `${protocol}//${host}/new?from=${path}`
    })
}

const newButton = document.getElementById("menu-new")
if (newButton){
    newButton.addEventListener("click", () => {
        const protocol = window.location.protocol
        const host = window.location.host
        window.location.href = `${protocol}//${host}/new`
    })
}

const content = document.getElementById("codeZone")
if (content) {
    content.addEventListener("keydown", () => {
        showNumList(content.value.split('\n').length)
    })
    content.addEventListener("keyup", () => {
        showNumList(content.value.split('\n').length)
    })
    content.addEventListener("keypress", () => {
        showNumList(content.value.split('\n').length)
    })
    content.addEventListener("copy", () => {
        showNumList(content.value.split('\n').length)
    })
    content.addEventListener("paste", () => {
        showNumList(content.value.split('\n').length)
    })
    content.addEventListener("cut", () => {
        showNumList(content.value.split('\n').length)
    })
}

const saveButton = document.getElementById("menu-save")
if (saveButton) {
    const title = document.getElementById("title")
    const description = document.getElementById("description")
    const publicCheck = document.getElementById("public")
    const expire = document.getElementById("expiration").value
    saveButton.addEventListener("click", () => {
        const myHeaders = new Headers();
        myHeaders.append("Content-Type", "application/json");

        if (!content.value) return alert("Contenu du texto vide")
        if (!title.value) return alert("Titre du texto vide")
        if (!description.value) return alert("Description du texto vide")

        const raw = JSON.stringify({
            "content": content.value,
            "title": title.value,
            "description": description.value,
            "public": publicCheck.checked,
            "expire": expire !== 0 ? Date.now() + (expire*1000) : null
        });
        const requestOptions = {method: "POST", headers: myHeaders, body: raw, redirect: "follow"};
        const protocol = window.location.protocol
        const host = window.location.host
        console.log(requestOptions)
        fetch(`${protocol}//${host}/new-texto/user`, requestOptions)
            .then((response) => response.text())
            .then((result) => window.location.href = result)
            .catch((error) => alert(error));
    })
}

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