import { browserSupportsWebAuthn, startAuthentication, startRegistration } from "@simplewebauthn/browser";
import { api } from "./api";

export const passkeysSupported = () => browserSupportsWebAuthn();

/** Annulation par l'utilisateur (fenêtre fermée, délai dépassé) : pas une erreur à afficher. */
export const isPasskeyCancel = (e: unknown) =>
  e instanceof Error && (e.name === "NotAllowedError" || e.name === "AbortError" || (e as { code?: string }).code === "ERROR_CEREMONY_ABORTED");

/** Crée une passkey pour le compte connecté. */
export async function registerPasskey(name: string) {
  const { requestId, publicKey } = await api.passkeyRegisterOptions();
  const r = await startRegistration({ optionsJSON: publicKey as never });
  // On n'envoie que les champs standard attendus par le serveur.
  const credential = {
    id: r.id, rawId: r.rawId, type: r.type, authenticatorAttachment: r.authenticatorAttachment,
    clientExtensionResults: r.clientExtensionResults ?? {},
    response: { clientDataJSON: r.response.clientDataJSON, attestationObject: r.response.attestationObject, transports: r.response.transports ?? [] },
  };
  return api.passkeyRegister(requestId, credential, name);
}

/** Connexion avec une passkey découvrable (aucun identifiant à saisir). */
export async function loginWithPasskey() {
  const { requestId, publicKey } = await api.passkeyLoginOptions();
  const r = await startAuthentication({ optionsJSON: publicKey as never });
  const credential = {
    id: r.id, rawId: r.rawId, type: r.type, authenticatorAttachment: r.authenticatorAttachment,
    clientExtensionResults: r.clientExtensionResults ?? {},
    response: {
      clientDataJSON: r.response.clientDataJSON, authenticatorData: r.response.authenticatorData,
      signature: r.response.signature, userHandle: r.response.userHandle,
    },
  };
  await api.passkeyLogin(requestId, credential);
}
