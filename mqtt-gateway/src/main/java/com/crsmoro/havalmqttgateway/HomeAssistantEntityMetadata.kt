package com.crsmoro.havalmqttgateway

/** Exact OEM identifiers are kept separate from translated presentation. */
internal object HomeAssistantEntityMetadata {
    private val names: Map<String, String> = """
car.basic.accumulated_drivetime|Tempo acumulado de condução
car.basic.accumulated_odometer|Odômetro acumulado
car.basic.avg_fuel_consumption|Consumo médio de combustível
car.basic.battery_power_level|Nível da bateria auxiliar
car.basic.battery_voltage|Tensão da bateria auxiliar
car.basic.coolant_temp|Temperatura do líquido de arrefecimento
car.basic.coolant_temp_warning|Alerta da temperatura do líquido de arrefecimento
car.basic.cur_journey_avg_fuel_consume|Consumo médio de combustível da viagem atual
car.basic.door_lock_status|Estado das travas das portas
car.basic.door_status|Estado das portas
car.basic.driving_ready_state|Estado de prontidão para dirigir
car.basic.engine_oil_low_pressure_warning|Alerta de baixa pressão do óleo do motor
car.basic.engine_speed|Rotação do motor
car.basic.engine_state|Estado do motor
car.basic.epb_state|Estado do freio de estacionamento eletrônico
car.basic.fatigue_warning|Alerta de fadiga
car.basic.front_fog_light_status|Estado do farol de neblina dianteiro
car.basic.gear_status|Estado da marcha
car.basic.hand_brake_status|Estado do freio de estacionamento
car.basic.hazard_light_status|Estado do pisca-alerta
car.basic.head_light_status|Estado dos faróis
car.basic.high_beam_light_status|Estado do farol alto
car.basic.inside_temp|Temperatura interna
car.basic.instant_fuel_consumption|Consumo instantâneo de combustível
car.basic.left_turn_Indicator_light_status|Indicador de seta esquerda no painel
car.basic.left_turn_light_status|Estado da seta esquerda
car.basic.low_beam_light_status|Estado do farol baixo
car.basic.low_light_status|Estado da luz de posição
car.basic.maintenance_warning|Alerta de manutenção
car.basic.maintenance_warning_mileage|Quilometragem do alerta de manutenção
car.basic.oil_low_warning|Alerta de nível baixo de óleo
car.basic.outside_temp|Temperatura externa
car.basic.rear_fog_light_status|Estado do farol de neblina traseiro
car.basic.remain_fuel_percentage|Combustível restante
car.basic.remain_odometer|Autonomia restante
car.basic.right_turn_Indicator_light_status|Indicador de seta direita no painel
car.basic.right_turn_light_status|Estado da seta direita
car.basic.seat_belt_warning|Alerta do cinto de segurança
car.basic.seated_state|Estado de ocupação dos bancos
car.basic.steering_reset_remind_enable|Lembrete de centralização da direção
car.basic.steering_wheel_angle|Ângulo do volante
car.basic.sunroof_status|Estado do teto solar
car.basic.sunshade_status|Estado da cortina solar
car.basic.tirepress_warning|Alerta de pressão dos pneus
car.basic.tiretemp_warning|Alerta de temperatura dos pneus
car.basic.total_odometer|Odômetro total
car.basic.tpms_status|Estado do monitoramento dos pneus
car.basic.tpms_units|Unidades do monitoramento dos pneus
car.basic.tpms_warning|Alerta do monitoramento dos pneus
car.basic.vehicle_speed|Velocidade do veículo
car.basic.vehicle_speed_since_reset|Velocidade do veículo desde a reinicialização
car.basic.vin_code|Número de identificação do veículo
car.basic.window_status|Estado das janelas
car.comfort_setting.driver_seat_ventilation_level|Nível de ventilação do banco do motorista
car.comfort_setting.passenger_seat_ventilation_level|Nível de ventilação do banco do passageiro
car.comfort_setting.seat_ventilation_max_level|Nível máximo de ventilação dos bancos
car.configure.auto_headlight|Configuração do farol automático
car.configure.comb_front_light_src|Configuração da fonte da iluminação dianteira
car.configure.ev_drive_architecture|Configuração da arquitetura de tração elétrica
car.configure.light_auto_switch_system|Configuração da comutação automática de luzes
car.configure.nominal_tire_pressure|Pressão nominal dos pneus configurada
car.configure.parking_light|Configuração da luz de estacionamento
car.dms.camera_shelter|Obstrução da câmera do motorista
car.dms.feedback.distracted_state|Estado de distração do motorista
car.dms.feedback.emotional_state|Estado emocional do motorista
car.dms.feedback.fatigue_driving_state|Estado de fadiga do motorista
car.dms.frs.config_enable|Configuração do reconhecimento do motorista
car.dms.frs.driver_age|Idade do motorista (reconhecimento)
car.dms.frs.driver_behavior_detect|Detecção de comportamento do motorista (reconhecimento)
car.dms.frs.driver_gender|Gênero do motorista (reconhecimento)
car.dms.frs.driver_sight_to_monitor_enable|Monitoramento do olhar do motorista
car.dms.frs.driver_sight_to_monitor_result|Resultado do monitoramento do olhar
car.dms.frs.driver_stat_state|Estado do motorista (reconhecimento)
car.dms.frs.enroll_age|Idade no cadastro do motorista
car.dms.frs.enroll_gender|Gênero no cadastro do motorista
car.dms.frs.enroll_num|Número de cadastros do motorista
car.dms.frs.gaze_area|Área do olhar do motorista
car.dms.frs.notify_delte_state|Notificação de exclusão de cadastro do motorista
car.dms.frs.notify_enroll_state|Notificação de cadastro do motorista
car.dms.frs.notify_identify_result|Resultado da identificação do motorista
car.dms.frs.notify_identify_state|Estado da identificação do motorista
car.dms.infraredblocking_glasses_detect|Detecção de óculos que bloqueiam infravermelho
car.dms.work_state|Estado do monitoramento do motorista
car.drive.setting.outline_lamps_state|Estado das luzes de contorno
car.drive.setting.outside_view_mirror_fold_state|Estado de recolhimento dos retrovisores
car.drive_setting.drive_mode|Modo de condução
car.drive_setting.esp_enable|Controle eletrônico de estabilidade
car.drive_setting.steering_wheel_assist_mode|Modo de assistência da direção
car.ev.setting.avas_config|Configuração do aviso acústico do veículo elétrico
car.ev.setting.avas_enable|Aviso acústico do veículo elétrico
car.ev.setting.pedal_control_enable|Controle por pedal
car.ev_info.Instant_energy_consumption|Consumo instantâneo de energia
car.ev_info.avg_energy_consume_info_since_startup|Consumo médio de energia desde a partida
car.ev_info.battery_charge_percentage|Carga da bateria de tração
car.ev_info.charging_state|Estado do carregamento
car.ev_info.cur_battery_power_percentage|Nível atual da bateria de tração
car.ev_info.cur_charge_current|Corrente de carregamento
car.ev_info.cycle_energy_consume_info|Consumo de energia do ciclo
car.ev_info.cycle_fuel_consume_info|Consumo de combustível do ciclo
car.ev_info.electric_mode_remain_odometer|Autonomia elétrica restante
car.ev_info.energy_drive_state|Estado de tração energética
car.ev_info.energy_output_percentage|Percentual de saída de energia
car.ev_info.energy_recovery_info|Informações de recuperação de energia
car.ev_info.fuel_consume_info|Informações de consumo de combustível
car.ev_info.fuel_mode_remain_odometer|Autonomia restante a combustível
car.ev_info.motor_power|Potência do motor elétrico
car.ev_info.motor_speed|Rotação do motor elétrico dianteiro
car.ev_info.power_battery_current|Corrente da bateria de tração
car.ev_info.power_battery_voltage|Tensão da bateria de tração
car.ev_info.rear_motor_speed|Rotação do motor elétrico traseiro
car.ev_info.soc_of_battery|Estado de carga da bateria de tração
car.ev_setting.charge_soc_target_config|Configuração da meta de carga da bateria
car.ev_setting.energy_recovery_level|Nível de recuperação de energia
car.ev_setting.power_model_config|Configuração do modo de potência
car.ev_setting.power_reserve_config|Configuração da reserva de energia
car.frs_setting.distraction_detection_enable|Detecção de distração configurada
car.frs_setting.dms_enable|Monitoramento do motorista configurado
car.frs_setting.driver_age|Idade do motorista configurada
car.frs_setting.driver_behavior_detect|Detecção de comportamento do motorista configurada
car.frs_setting.driver_gender|Gênero do motorista configurado
car.frs_setting.expression_recognition_enable|Reconhecimento de expressão configurado
car.frs_setting.facial_detection_enable|Detecção facial configurada
car.frs_setting.fatigue_monitoring_enable|Monitoramento de fadiga configurado
car.frs_setting.occupant_identification_system|Sistema de identificação dos ocupantes configurado
car.frs_setting.oms_age|Idade do ocupante configurada
car.frs_setting.oms_exist_state|Estado de presença do ocupante configurado
car.frs_setting.oms_gender|Gênero do ocupante configurado
car.frs_setting.passenger_expression|Expressão do passageiro configurada
car.frs_setting.screen_control_enable|Controle de tela configurado
car.hvac.ac_enable|Ar-condicionado
car.hvac.acmax_enable|Ar-condicionado máximo
car.hvac.anion_enable|Ionizador de ar
car.hvac.aqs_enable|Controle de qualidade do ar
car.hvac.auto_enable|Climatização automática
car.hvac.blower_mode|Modo de distribuição de ar
car.hvac.cycle_mode|Modo de recirculação de ar
car.hvac.driver_temperature|Temperatura selecionada do motorista
car.hvac.fan_speed|Velocidade da ventilação
car.hvac.fan_speed_range|Faixa de velocidade da ventilação
car.hvac.front_defrost_enable|Desembaçador dianteiro
car.hvac.front_temperature_range|Faixa de temperatura dianteira
car.hvac.heating_enable|Aquecimento da climatização
car.hvac.panel_display_notify|Notificação do painel de climatização
car.hvac.pass_temperature|Temperatura selecionada do passageiro
car.hvac.pm2.5_value|Partículas finas PM2,5
car.hvac.power_mode|Modo de alimentação da climatização
car.hvac.rear_defrost_enable|Desembaçador traseiro
car.hvac.setting.auto_defrost_enable|Desembaçamento automático configurado
car.hvac.setting.comfort_curve|Curva de conforto da climatização
car.hvac.setting.limit_enable|Limite da climatização configurado
car.hvac.sync_enable|Sincronização da climatização
car.intelligent_driving_info.auto_hold_state|Estado da retenção automática do freio
car.ipk_info.bsd_lca_warning_reqleft|Alerta esquerdo de ponto cego e mudança de faixa
car.ipk_info.bsd_lca_warning_reqright|Alerta direito de ponto cego e mudança de faixa
car.ipk_info.dow_warning_reqleft|Alerta esquerdo de abertura de porta
car.ipk_info.dow_warning_reqright|Alerta direito de abertura de porta
car.ipk_info.fcta_warning|Alerta de tráfego cruzado dianteiro
car.ipk_info.fcw_warning|Alerta de colisão frontal
car.ipk_info.warning_tts_notify|Notificação de alerta por voz
car.ipk_light.door_warning|Indicador de alerta de portas no painel
car.ipk_light.engine_oil_low_pressure_warning|Indicador de baixa pressão do óleo no painel
car.ipk_light.fuel_low|Indicador de combustível baixo no painel
car.ipk_light.seat_belt_warning_indicator|Indicador do cinto de segurança no painel
car.ipk_light.tpms_warning|Indicador de pressão dos pneus no painel
car.ipk_setting.brightness_config|Configuração do brilho do painel
car.oms.age_recognition_enable|Reconhecimento de idade do ocupante
car.oms.expression_recognition_enable|Reconhecimento de expressão do ocupante
car.oms.feedback.age_state|Idade do ocupante (monitoramento)
car.oms.feedback.expression_state|Expressão do ocupante (monitoramento)
car.oms.feedback.gender_state|Gênero do ocupante (monitoramento)
car.oms.frs.codriver_expression|Expressão do passageiro dianteiro
car.oms.frs.oms_facedb|Cadastro facial dos ocupantes
car.oms.frs.oms_facedb_num|Número de cadastros faciais dos ocupantes
car.oms.frs.oms_number|Número de ocupantes reconhecidos
car.oms.frs.passengerL_row1_expression|Expressão do ocupante esquerdo da primeira fileira
car.oms.frs.passengerM_row1_expression|Expressão do ocupante central da primeira fileira
car.oms.frs.passengerR_row1_expression|Expressão do ocupante direito da primeira fileira
car.oms.frs.seat_staff_info|Informações de ocupação dos bancos
car.oms.gender_recognition_enable|Reconhecimento de gênero do ocupante
haval.power.ice|Motor a combustão
haval.power.flow|Fluxo de potência
    """.let(::parseNames)

    fun parseNames(raw: String): Map<String, String> = buildMap {
        for (line in raw.trimIndent().lineSequence()) {
            val (key, value) = line.split('|', limit = 2)
            require(key !in this) { "Duplicate entity metadata: $key" }
            put(key, value)
        }
    }

    fun name(key: String): String = names[key] ?: key

    fun icon(key: String): String? {
        if (key !in names) return null
        return when {
            key == "haval.power.ice" || key.contains("engine_speed") || key.contains("engine_state") -> "mdi:engine"
            key.contains("vehicle_speed") -> "mdi:speedometer"
            key == "car.basic.battery_voltage" || key == "car.basic.battery_power_level" -> "mdi:car-battery"
            key.startsWith("car.hvac.") -> "mdi:air-conditioner"
            key.startsWith("car.dms.") || key.startsWith("car.oms.") || key.startsWith("car.frs_setting.") -> "mdi:account"
            key.contains("warning") -> "mdi:alert"
            key.startsWith("car.ev") || key == "haval.power.flow" -> "mdi:ev-station"
            key.contains("fuel") || key.contains("oil") -> "mdi:gas-station"
            key.contains("temp") -> "mdi:thermometer"
            key.contains("light") || key.contains("lamp") -> "mdi:car-light-high"
            key.contains("tire") || key.contains("tpms") -> "mdi:car-tire-alert"
            key.contains("door") -> "mdi:car-door"
            key.contains("seat") -> "mdi:car-seat"
            key.contains("odometer") || key.contains("mileage") -> "mdi:counter"
            else -> "mdi:car-info"
        }
    }

    // Exact allowlist: migration code can inspect Message.topic for binary_sensor.
    private val binaryHvacKeys = setOf(
        "ac_enable", "acmax_enable", "anion_enable", "aqs_enable", "auto_enable",
        "front_defrost_enable", "heating_enable", "rear_defrost_enable", "sync_enable",
        "setting.auto_defrost_enable", "setting.limit_enable"
    ).map { "car.hvac.$it" }.toSet()
    fun isBinaryHvac(key: String): Boolean = key in binaryHvacKeys
}
