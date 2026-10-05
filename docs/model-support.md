# Model support / Soporte de modelos

## For users / Para usuarios

E-Pad Reset runs offline and has no `INTERNET` permission. A missing model is a request for investigation, not a promise that a reset can be made safe. Maintenance-box models can request cleaning/nozzle support, not a reset of the box chip.

E-Pad Reset funciona sin conexión y no tiene permiso `INTERNET`. Un modelo ausente es una petición de investigación, no una promesa de reset seguro. Para modelos con caja se puede investigar limpieza e inyectores, no reiniciar el chip de la caja.

1. Use **Help add this model / Ayudar a añadir este modelo** when connection reports a missing model.
2. Review and optionally edit the local draft. It contains only model fields, USB VID/PID, app/Android versions and a summary of the catalog failure. No raw logs, serial numbers or EEPROM backups are attached. Check anything you add yourself.
3. Choose how to share. **Copy report / Copiar informe** copies only on request.
   - [Discord community / Comunidad Discord](https://discord.gg/NYqqn4b25): requires an account. Open the invite, ask where to post a model request and paste the reviewed report. Opening the link does not send the report.
   - [GitHub model request / Petición de modelo](https://github.com/DSGM-GMSD/e-padreset/issues/new?template=01-model-support.yml): requires an account. Search for your model, then paste the report into the template. The link contains no report/model data.
   - **Share report / Compartir informe**: use another app with someone who can relay the request. The chooser needs no GitHub/Discord account, but the receiving app may need an account. There is no dedicated support inbox yet; choosing an app does not guarantee delivery to a maintainer.
4. Do not post raw protocol logs, EEPROM dumps/backups, serial numbers or unredacted photos. Do not try guessed keys, addresses or experimental EEPROM writes.

Revisa el borrador, copia o comparte solo al decidirlo y elige el destinatario. El navegador u otra app realizan las conexiones de red bajo sus propias políticas. No publiques logs crudos, volcados, respaldos ni números de serie; no pruebes escrituras adivinadas.

## Discord setup checklist / Configuración pendiente de Discord

This document does **not** configure the server. An administrator can create a Community-enabled forum named **Modelos no soportados / Unsupported models**. Until it exists, the app tells users to ask where to post rather than pointing to a nonexistent channel.

Este documento **no** configura el servidor. Un administrador puede crear un foro con Community habilitado. Hasta entonces, la app indica que se pregunte dónde publicar.

- One post per model; search before creating. / Una publicación por modelo; buscar antes.
- Pin the instructions below; allow members to post. / Fijar instrucciones y permitir publicar.
- Suggested tags: `Request / Petición`, `Needs data / Faltan datos`, `Read-only volunteers / Voluntarios lectura`, `Review / En revisión`, `Available / Disponible`.
- Do not demand a second GitHub submission. / No exigir otra petición en GitHub.
- Tell users posts are visible to the community. / Indicar la visibilidad comunitaria.
- Keep the invite valid; replace the app link if it changes. / Mantener válida la invitación.

### Copyable forum instructions / Instrucciones para copiar al foro

> Search your model first. Title your post `Epson [model] — support request`.
> Use the app's limited report, review it, and paste it below. Do not use Copy log or upload EEPROM backups.
> State which function you want and whether you can help with information or read-only tests. You do not need to know any EEPROM keys or addresses.
> Support is not guaranteed. Do not run experimental writes. A maintainer can record this request on GitHub for you and reply with the link.
>
> Busca primero tu modelo. Título: `Epson [modelo] — petición de soporte`.
> Revisa y pega el informe limitado de la app; no uses Copiar log ni adjuntes respaldos EEPROM.
> Indica la función deseada y si puedes aportar información o pruebas solo de lectura. No necesitas conocer claves ni direcciones EEPROM.
> No hay garantía de soporte. No ejecutes escrituras experimentales. Un mantenedor puede registrar la petición en GitHub y devolverte el enlace.

```text
Model / Modelo:
Wanted function / Función deseada:
Can help / Puedo ayudar: information / read-only tests / not now
Replaceable maintenance box / Caja reemplazable: yes / no / unknown
Public source or supporting tool / Fuente pública o herramienta:

Reviewed limited app report / Informe limitado revisado:
[paste here / pegar aquí]
```

## Maintainer triage / Revisión de mantenedores

1. Find/create one canonical GitHub issue per model. Merge duplicate evidence there; link it back in Discord. A user need not have GitHub to participate.
2. Relay only the reviewed technical report, not usernames, email addresses or unrelated conversation. Ask before copying additional material between audiences.
3. Separate **requested**, **data sourced/reviewed**, **implemented**, **read-tested**, **write-tested on hardware** and **released**. Record the capability and hardware evidence, not a blanket "supported" label.
4. Verify the exact model resolution and reliable provenance of read keys, write keys, EEPROM addresses and baseline values before catalog changes. A model name/report alone cannot determine a safe write plan. Never silently alias a model on similarity alone.
5. Prioritize read-only diagnostics. Any later write test requires an informed volunteer, a complete backup and physical maintenance considerations. No user is expected to agree to it to request support.
6. Keep maintenance-box requests separate from internal pad reset requests. No promise to reset the box chip.
7. Respond with status/link/release when known, without promising a schedule.
8. A local profile is a gap on that device only. The issue may cite the public URL and version. It must not contain read keys, write keys or EEPROM addresses, and a maintainer must not ask for them in the issue. Promote the model by fetching that public source and running `scripts/sync-model-data.mjs`. An exact catalog name is never a gap: `PM-4000PX` and `WF-C5290BA` already have exact entries, and a shorter paste does not fill a longer model.

Un mantenedor centraliza la evidencia, responde con el enlace y separa los estados de investigación, implementación y pruebas por función. Trasladar una petición no autoriza a publicar datos personales ni ejecutar escrituras. El issue puede citar la URL y la versión de un perfil local; no lleva claves ni direcciones. No hay plazo garantizado.

## Future account-free intake / Recepción futura sin cuentas nuevas

A dedicated email address or an external no-login form can be added after its owner, privacy terms, spam handling and reply workflow are chosen. Neither needs networking inside the Android app. No address, form, bot, Discord channel or GitHub issue is provisioned/published by this change.

Se puede añadir correo dedicado o formulario externo cuando exista un responsable, política y flujo de respuesta. No requieren red en la app. Este cambio no crea ni publica cuentas, canales ni peticiones remotas.
