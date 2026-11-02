# Haval MQTT Gateway

Aplicativo Android independente para a central do Haval. Lê propriedades veiculares por meio do serviço OEM e do Shizuku e as publica em MQTT. Não depende do Haval App Tool, não usa seus broadcasts, não publica dados de navegação ou Android Auto e não envia comandos ao veículo.

## Requirements

- Android 9 / API 28 or newer (built with compile SDK 36).
- Shizuku em execução na central e autorizado especificamente para o Haval MQTT Gateway. A disponibilidade do serviço OEM e das propriedades depende da central e do firmware.
- An MQTT broker reachable from the head unit. Configure TLS with `ssl://host:8883`. Anonymous `tcp://` is allowed only on a trusted local network and is shown as insecure; the gateway rejects TCP when a username or password is configured.

## Setup

1. Instale `mqtt-gateway-debug.apk` na central e inicie o Shizuku.
2. Abra **Haval MQTT Gateway**, selecione **Autorizar acesso veicular via Shizuku** e conceda a permissão.
3. Configure a URI do broker, usuário/senha opcionais e prefixo dos tópicos. Use TLS e credenciais específicas com ACL apenas de publicação; a senha é protegida pelo Android Keystore.
4. Se desejado, marque **Publicar no Home Assistant** para criar automaticamente um dispositivo MQTT e sensores para os valores observados. Ative a inicialização automática apenas se desejado e selecione **Salvar e iniciar gateway**.
5. Confira o estado na tela ou notificação. O serviço veicular OEM deve estar disponível para o Shizuku.

O prefixo padrão é `gwm`. O gateway aguarda um VIN válido e publica em `<prefixo>/haval/<VIN>/<chave>`. O prefixo antigo padrão `haval/telemetry` é migrado para `gwm`; outros prefixos existentes são preservados. A alteração dos tópicos exige atualizar consumidores antigos.

Example topics:

```text
gwm/haval/<VIN>/car.basic.vehicle_speed
my-prefix/haval/<VIN>/car.basic.vehicle_speed
```

Payloads are JSON with `schema_version`, `vehicle_id` (the complete prefix including VIN), UTC observation timestamp, sequence, `app_api_version: 0`, key, raw string value, `source: "vehicle_oem_binder"` and `source_trust: "shizuku_oem_binder"`. Derived signals use `vehicle_derived` and `derived_from_shizuku_oem_binder`. MQTT client IDs derive from the prefix and a random, locally persisted installation ID; updating changes the broker session ID. Telemetry uses QoS 1 and is not retained. The app does not subscribe to MQTT command topics.

Com a opção do Home Assistant, mensagens de configuração MQTT Discovery são publicadas sob `homeassistant/` com QoS 1 e retenção, usando os tópicos de telemetria como estados. O Home Assistant precisa estar conectado ao mesmo broker, com MQTT Discovery habilitado. Desmarcar a opção interrompe novas publicações de Discovery, mas mantém as configurações retidas e entidades existentes. Valores sem semântica comprovada são sensores genéricos; `haval.power.ice` é um sensor binário inferido, não uma leitura de ignição. Como os estados não são retidos, o gateway republica observações em cache após o Discovery e a cada 60 segundos enquanto a fonte estiver conectada. Essas mensagens têm `cached: true` e mantêm o horário original; não garantem leitura nova.

## Data/privacy limits

- Os dados publicados podem incluir VIN e informações de ocupantes. Proteja o broker com TLS e ACLs restritivas; o prefixo também é copiado para `vehicle_id`. TCP anônimo expõe os dados na rede.
- A seleção de propriedades veiculares está no código. Uma propriedade catalogada não garante que exista, seja legível ou produza valores no seu veículo. A compatibilidade de Shizuku, serviço OEM e firmware precisa ser testada na central.
- Não publica `app.*`, navegação, Android Auto, telefone, dados de SIM ou rede. `haval.power.ice` e `haval.power.flow` são inferências, não medições. O fluxo requer RPM, carregamento e um estado OEM reconhecido; para estados desconhecidos não é inventado um valor. Não reproduz a inferência AWD do Haval App Tool e precisa de validação no veículo.
- A leitura usa Binder do serviço OEM via Shizuku e não executa operações de comando. Não use a telemetria para decisões críticas de segurança.
- Sem rede, o gateway mantém o último valor por chave em memória, limitado a 2.048 chaves e aproximadamente 1 MiB. Ao reconectar ao MQTT, republica as últimas observações com seus horários originais; elas podem estar desatualizadas. Não é armazenamento durável de histórico.

## Build and tests

```sh
./gradlew :mqtt-gateway:testDebugUnitTest
./gradlew :mqtt-gateway:assembleDebug
```

The debug APK is produced at `mqtt-gateway/build/outputs/apk/debug/mqtt-gateway-debug.apk`.
