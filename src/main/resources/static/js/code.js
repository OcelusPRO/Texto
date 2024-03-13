function get_url_extension( url ) {
    return url.split(/[#?]/)[0].split('.').pop().trim();
}

document.getElementById("codeZone").classList.add(get_url_extension(document.URL))
