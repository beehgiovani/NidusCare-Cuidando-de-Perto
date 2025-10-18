const { onCall, onRequest } = require("firebase-functions/v2/https" );
const { onDocumentUpdated, onDocumentWritten } = require("firebase-functions/v2/firestore");
const { onSchedule } = require("firebase-functions/v2/scheduler");
const { logger } = require("firebase-functions");
const admin = require("firebase-admin");
const { VertexAI } = require("@google-cloud/vertexai");
const { HttpsError } = require("firebase-functions/v2/https" );
const { onMessagePublished } = require("firebase-functions/v2/pubsub");
const { google } = require("googleapis");

const ANDROID_PACKAGE_NAME = "com.developersbeeh.medcontrol";

admin.initializeApp();

// --- INÍCIO DO CÓDIGO DE AUTENTICAÇÃO ---
let isPlayApiInitialized = false;
let auth;

async function initGooglePlayPublisher() {
    if (isPlayApiInitialized) {
        return;
    }
    logger.info("🔑 Autenticando com a API do Google Play Developer...");
    try {
        if (!auth) {
            auth = new google.auth.GoogleAuth({
                scopes: ["https://www.googleapis.com/auth/androidpublisher"],
            } );
        }
        const authClient = await auth.getClient();
        google.options({ auth: authClient });
        isPlayApiInitialized = true;
        logger.info("✅ API do Google Play Developer autenticada com sucesso.");
    } catch (error) {
        logger.error("❌ Falha ao autenticar com a API do Google Play:", error);
        throw new Error("Não foi possível inicializar o cliente da Google Play API.");
    }
}
// --- FIM DO CÓDIGO DE AUTENTICAÇÃO ---

const publisher = google.androidpublisher("v3");

// --- INÍCIO DAS FUNÇÕES AUXILIARES ---

const getUTCDate = (dateInput) => {
    if (typeof dateInput === 'string' && dateInput.includes('-')) {
        const parts = dateInput.split('-').map(Number);
        return new Date(Date.UTC(parts[0], parts[1] - 1, parts[2]));
    }
    const d = new Date(dateInput);
    return new Date(Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate()));
};

const isMedicationDay = (med, date) => {
    const treatmentStartUTC = getUTCDate(med.dataInicioTratamentoString);
    const dateUTC = getUTCDate(date);
    if (dateUTC < treatmentStartUTC) return false;
    switch (med.frequenciaTipo) {
        case "DIARIA":
            return true;
        case "SEMANAL":
            const jsDayOfWeek = dateUTC.getUTCDay();
            const appDayOfWeek = jsDayOfWeek === 0 ? 7 : jsDayOfWeek;
            return med.diasSemana.includes(appDayOfWeek);
        case "INTERVALO_DIAS":
            if (med.frequenciaValor <= 0) return false;
            const diffTime = dateUTC.getTime() - treatmentStartUTC.getTime();
            const diffDays = Math.floor(diffTime / (1000 * 60 * 60 * 24));
            return diffDays % med.frequenciaValor === 0;
        default:
            return true;
    }
};

const calculateExpectedDosesForMedication = (med, periodStart, periodEnd) => {
    if (med.isUsoEsporadico || !med.horarios || med.horarios.length === 0) {
        return 0;
    }
    const treatmentStart = getUTCDate(med.dataInicioTratamentoString);
    let treatmentEnd = getUTCDate(periodEnd);
    if (!med.isUsoContinuo && med.duracaoDias > 0) {
        const end = new Date(treatmentStart);
        end.setUTCDate(end.getUTCDate() + med.duracaoDias - 1);
        treatmentEnd = end;
    }
    let effectiveStart = new Date(treatmentStart > periodStart ? treatmentStart : periodStart);
    let effectiveEnd = new Date(treatmentEnd < periodEnd ? treatmentEnd : periodEnd);
    if (effectiveStart > effectiveEnd) {
        return 0;
    }
    let totalExpected = 0;
    let currentDate = new Date(effectiveStart);
    while (currentDate <= effectiveEnd) {
        if (isMedicationDay(med, currentDate)) {
            if (currentDate.getTime() === treatmentStart.getTime() && med.dataCriacao && typeof med.dataCriacao === 'string') {
                const creationDateTime = new Date(med.dataCriacao);
                if (!isNaN(creationDateTime.getTime())) {
                    const creationTimeInMinutes = creationDateTime.getUTCHours() * 60 + creationDateTime.getUTCMinutes();
                    med.horarios.forEach(horarioStr => {
                        const [hour, minute] = horarioStr.split(':').map(Number);
                        const horarioInMinutes = hour * 60 + minute;
                        if (horarioInMinutes >= creationTimeInMinutes) {
                            totalExpected++;
                        }
                    });
                } else {
                    totalExpected += med.horarios.length;
                }
            } else {
                totalExpected += med.horarios.length;
            }
        }
        currentDate.setUTCDate(currentDate.getUTCDate() + 1);
    }
    return totalExpected;
};

const calculateExpectedDosesForPeriod = (medicamentos, startDate, endDate) => {
    let totalExpected = 0;
    const start = getUTCDate(startDate);
    const end = getUTCDate(endDate);
    medicamentos.forEach(med => {
        totalExpected += calculateExpectedDosesForMedication(med, start, end);
    });
    return totalExpected;
};

function calculateNextDoseTimeJS(med) {
    if (med.isUsoEsporadico || med.isPaused || !med.horarios || med.horarios.length === 0) {
        return null;
    }
    const now = new Date();
    const nowTimestamp = now.getTime();
    const sortedHorarios = med.horarios.sort();
    for (let i = 0; i < 365; i++) {
        const checkDate = new Date();
        checkDate.setUTCDate(checkDate.getUTCDate() + i);
        if (isMedicationDay(med, checkDate)) {
            for (const horarioStr of sortedHorarios) {
                const [hour, minute] = horarioStr.split(':').map(Number);
                const nextDoseCandidateTimestamp = Date.UTC(
                    checkDate.getUTCFullYear(),
                    checkDate.getUTCMonth(),
                    checkDate.getUTCDate(),
                    hour,
                    minute
                );
                if (nextDoseCandidateTimestamp > nowTimestamp) {
                    return new Date(nextDoseCandidateTimestamp);
                }
            }
        }
    }
    return null;
}

function calculateAgeFromDobString(dobString) {
    if (!dobString || typeof dobString !== 'string') return null;
    try {
        let year, month, day;
        if (dobString.includes('/')) {
            [day, month, year] = dobString.split('/');
        } else {
            [year, month, day] = dobString.split('-');
        }
        if (!year || !month || !day || year.length < 4) return null;
        const birthDate = new Date(Date.UTC(parseInt(year), parseInt(month) - 1, parseInt(day)));
        if (isNaN(birthDate.getTime())) return null;
        const today = new Date();
        const todayUTC = new Date(Date.UTC(today.getUTCFullYear(), today.getUTCMonth(), today.getUTCDate()));
        let age = todayUTC.getUTCFullYear() - birthDate.getUTCFullYear();
        const m = todayUTC.getUTCMonth() - birthDate.getUTCMonth();
        if (m < 0 || (m === 0 && todayUTC.getUTCDate() < birthDate.getUTCDate())) {
            age--;
        }
        return age;
    } catch (e) {
        logger.warn("Não foi possível calcular a idade da string:", dobString, e);
        return null;
    }
}

async function getCaregiversToNotify(caregiverIds, settingKey) {
    if (!caregiverIds || caregiverIds.length === 0) {
        return [];
    }
    const usersSnapshot = await admin.firestore().collection("users").where(admin.firestore.FieldPath.documentId(), "in", caregiverIds).get();
    const caregiversToNotify = [];
    usersSnapshot.forEach(doc => {
        if (doc.data()?.[settingKey] !== false) {
            caregiversToNotify.push(doc.id);
        }
    });
    return caregiversToNotify;
}

async function sendNotificationToCaregivers(caregiverIds, payload) {
    if (!caregiverIds || caregiverIds.length === 0) {
        logger.warn("Nenhum ID de cuidador fornecido para notificação.");
        return;
    }

    const usersSnapshot = await admin.firestore().collection("users").where(admin.firestore.FieldPath.documentId(), "in", caregiverIds).get();

    const tokens = [];
    const userTokensMap = new Map();
    usersSnapshot.forEach((doc) => {
        const fcmToken = doc.data()?.fcmToken;
        if (fcmToken) {
            tokens.push(fcmToken);
            userTokensMap.set(fcmToken, doc.id);
        } else {
            logger.warn(`Cuidador ${doc.id} não possui um token FCM.`);
        }
    });

    if (tokens.length > 0) {
        const uniqueTokens = [...new Set(tokens)];
        const message = { ...payload, tokens: uniqueTokens };
        try {
            const response = await admin.messaging().sendEachForMulticast(message);
            logger.info(`${response.successCount} notificações enviadas com sucesso.`);

            if (response.failureCount > 0) {
                const tokensToDelete = [];
                response.responses.forEach((resp, idx) => {
                    if (!resp.success) {
                        const errorCode = resp.error.code;
                        if (errorCode === 'messaging/registration-token-not-registered' || errorCode === 'messaging/invalid-registration-token') {
                            const failedToken = uniqueTokens[idx];
                            tokensToDelete.push(failedToken);
                            const userId = userTokensMap.get(failedToken);
                            logger.warn(`Token inválido detectado para o usuário ${userId}. Agendando para remoção.`);
                        }
                    }
                });

                if (tokensToDelete.length > 0) {
                    const batch = admin.firestore().batch();
                    tokensToDelete.forEach(token => {
                        const userId = userTokensMap.get(token);
                        if (userId) {
                            const userRef = admin.firestore().collection('users').doc(userId);
                            batch.update(userRef, { fcmToken: admin.firestore.FieldValue.delete() });
                        }
                    });
                    await batch.commit();
                    logger.info(`${tokensToDelete.length} tokens inválidos foram removidos do Firestore.`);
                }
            }
        } catch (error) {
            logger.error("Falha ao enviar notificações multicast.", error);
        }
    }
}

// --- FIM DAS FUNÇÕES AUXILIARES ---


// --- INÍCIO DAS FUNÇÕES DE IA (GEMINI) ---

let generativeModel;
// ✅ CORREÇÃO: Função única para inicializar o modelo Gemini
const getGenerativeModel = () => {
    if (!generativeModel) {
        logger.info("Inicializando o cliente Vertex AI e o modelo Generative AI...");
        const vertex_ai = new VertexAI({
            project: process.env.GCLOUD_PROJECT,
            location: "us-central1",
        });
        generativeModel = vertex_ai.getGenerativeModel({
            model: "gemini-2.5-pro", // ✅ CORREÇÃO: Modelo correto
            generation_config: { max_output_tokens: 8192, temperature: 0.3, top_p: 0.95 },
        });
    }
    return generativeModel;
};

exports.analisarCaixaRemedio = onCall({
    cors: true,
    memory: "1GiB",
    timeoutSeconds: 300,
    minInstances: 0,
    // enforceAppCheck: true // Removido
}, async (request) => {
    if (!request.auth) {
        throw new HttpsError("unauthenticated", "Usuário não autenticado.");
    }
    const uid = request.auth.uid;
    const userDoc = await admin.firestore().collection("users").doc(uid).get();
    if (!userDoc.exists || userDoc.data().premium !== true) {
        throw new HttpsError("permission-denied", "Esta funcionalidade é exclusiva para usuários Premium.");
    }

    const { imageGcsUri } = request.data;
    if (!imageGcsUri) {
        throw new HttpsError("invalid-argument", "O URI da imagem no Google Cloud Storage é obrigatório.");
    }

    try {
        const generativeModel = getGenerativeModel();

        const bucketName = imageGcsUri.split('/')[2];
        const filePath = imageGcsUri.split('/').slice(3).join('/');

        const [metadata] = await admin.storage().bucket(bucketName).file(filePath).getMetadata();
        const mimeType = metadata.contentType;

        if (!mimeType || !mimeType.startsWith('image/')) {
             throw new HttpsError("invalid-argument", "O arquivo fornecido não é uma imagem válida.");
        }
        logger.info(`Analisando imagem: ${imageGcsUri} (Tipo: ${mimeType})`);

        const prompt = `
            Você é um assistente de IA especialista em analisar imagens de embalagens de medicamentos. Sua tarefa é um processo de duas etapas:
            **ETAPA 1: EXTRAÇÃO LITERAL DA IMAGEM**
            Primeiro, leia CUIDADOSAMENTE a imagem e extraia as seguintes informações VISÍVEIS:
            - O nome comercial completo, incluindo a dosagem (ex: "Tylenol 750mg").
            - O princípio ativo, se estiver visível.
            - O texto que descreve a quantidade (ex: "blister com 20 comprimidos", "frasco com 150 ml").
            - O número do lote, se visível.
            - A data de validade, se visível.
            **ETAPA 2: ESTRUTURAÇÃO E ENRIQUECIMENTO DOS DADOS**
            Agora, use as informações extraídas e seu conhecimento farmacêutico para preencher o seguinte formato JSON.
            Formato do JSON de saída:
            {
              "nome": "Nome completo do medicamento com dosagem",
              "estoque": "Apenas o NÚMERO de unidades (comprimidos, cápsulas) ou o volume em mL. DEVE SER UM NÚMERO.",
              "principioAtivo": "O princípio ativo do medicamento",
              "classeTerapeutica": "A classe terapêutica principal, baseada no seu conhecimento (ex: 'Analgésico', 'Antibiótico')",
              "anotacoes": "Uma linha sobre a principal indicação do medicamento, baseada no seu conhecimento (ex: 'Para alívio de dores e febre.')",
              "lote": "O número do lote, se encontrado",
              "validade": "A data de validade, se encontrada, no formato MM/AAAA"
            }
            REGRAS CRÍTICAS:
            1. A resposta DEVE SER APENAS o objeto JSON, sem nenhum texto, explicação ou \`\`\`json\`\`\` ao redor.
            2. Se uma informação não for encontrada na imagem ou não puder ser deduzida com segurança, retorne uma string vazia "" para aquele campo.
            3. Para "validade", extraia apenas o mês e o ano no formato MM/AAAA.
            4. Para "estoque", retorne um número inteiro.
        `;

        const imagePart = { fileData: { mimeType: mimeType, fileUri: imageGcsUri } };
        const req = { contents: [{ role: "user", parts: [{ text: prompt }, imagePart] }] };

        const result = await generativeModel.generateContent(req);
        const responseText = result?.response?.candidates?.[0]?.content?.parts?.[0]?.text?.trim();

        if (!responseText) {
            throw new Error("O modelo não retornou texto de resposta.");
        }

        const jsonMatch = responseText.match(/\{[\s\S]*\}/);
        if (!jsonMatch) {
            throw new Error("Não foi possível extrair um JSON válido da resposta do modelo.");
        }

        const remedioData = JSON.parse(jsonMatch[0]);
        logger.info("JSON extraído com sucesso:", remedioData);

        await admin.firestore().collection("analises_remedios").add({
            userId: uid,
            filePath: imageGcsUri.split('/').slice(3).join('/'),
            imageUri: imageGcsUri,
            remedioData,
            timestamp: admin.firestore.FieldValue.serverTimestamp(),
        });

        return remedioData;

    } catch (error) {
        logger.error("Erro ao analisar imagem:", error);
        await admin.firestore().collection("analises_remedios_erros").add({
            userId: uid,
            imageUri: imageGcsUri,
            error: error.message,
            timestamp: admin.firestore.FieldValue.serverTimestamp(),
        });
        throw new HttpsError("internal", "Não foi possível analisar a imagem. Tente novamente com uma foto mais nítida e bem iluminada.");
    }
});

// ✅ CORREÇÃO: Função padronizada para usar GCS URI
exports.analisarReceita = onCall({ cors: true, maxInstances: 10, memory: '1GiB', timeoutSeconds: 300, minInstances: 0, enforceAppCheck: true }, async (request) => {
    if (!request.auth) {
        throw new HttpsError("unauthenticated", "A função deve ser chamada por um usuário autenticado.");
    }

    // ✅ CORREÇÃO: Espera 'imageGcsUri' (gs://)
    const { imageGcsUri, alergiasConhecidas, condicoesPreexistentes, medicamentosAtuais } = request.data;
    if (!imageGcsUri) {
        throw new HttpsError("invalid-argument", "O GCS URI da imagem é obrigatório.");
    }

    try {
        const model = getGenerativeModel();
        const prompt = buildPrescriptionAnalysisPrompt(alergiasConhecidas, condicoesPreexistentes, medicamentosAtuais);

        // ✅ CORREÇÃO: Detecta o MimeType
        const bucketName = imageGcsUri.split('/')[2];
        const filePath = imageGcsUri.split('/').slice(3).join('/');
        const [metadata] = await admin.storage().bucket(bucketName).file(filePath).getMetadata();
        const mimeType = metadata.contentType;

        if (!mimeType || !mimeType.startsWith('image/')) {
             throw new HttpsError("invalid-argument", "O arquivo fornecido não é uma imagem válida.");
        }
        logger.info(`Analisando receita: ${imageGcsUri} (Tipo: ${mimeType})`);

        // ✅ CORREÇÃO: Usa 'fileData' (camelCase)
        const imageFilePart = { fileData: { mimeType: mimeType, fileUri: imageGcsUri } };

        const req = { contents: [{ role: "user", parts: [{ text: prompt }, imageFilePart] }] };
        const result = await model.generateContent(req);
        const rawAnalysis = result.response.candidates[0].content.parts[0].text;

        return parsePrescriptionAnalysis(rawAnalysis);

    } catch (error) {
        logger.error("Erro ao analisar receita médica:", error);
        throw new HttpsError("internal", "Ocorreu um erro ao analisar a receita.", { details: error.message });
    }
});

// ✅ CORREÇÃO: Função padronizada para usar GCS URI
exports.analisarRefeicao = onCall({ cors: true, memory: "1GiB", timeoutSeconds: 300, minInstances: 0, enforceAppCheck: true }, async (request) => {
    if (!request.auth) {
        throw new HttpsError("unauthenticated", "A função deve ser chamada por um usuário autenticado.");
    }

    // ✅ CORREÇÃO: Espera 'imageGcsUri' (gs://)
    const { imageGcsUri, healthProfile } = request.data;
    if (!imageGcsUri) {
        throw new HttpsError("invalid-argument", "O GCS URI da imagem é obrigatório.");
    }

    try {
        const model = getGenerativeModel();
        const prompt = buildMealAnalysisPrompt(healthProfile || {});

        // ✅ CORREÇÃO: Detecta o MimeType
        const bucketName = imageGcsUri.split('/')[2];
        const filePath = imageGcsUri.split('/').slice(3).join('/');
        const [metadata] = await admin.storage().bucket(bucketName).file(filePath).getMetadata();
        const mimeType = metadata.contentType;

        if (!mimeType || !mimeType.startsWith('image/')) {
             throw new HttpsError("invalid-argument", "O arquivo fornecido não é uma imagem válida.");
        }
        logger.info(`Analisando refeição: ${imageGcsUri} (Tipo: ${mimeType})`);

        // ✅ CORREÇÃO: Usa 'fileData' (camelCase)
        const imageFilePart = { fileData: { mimeType: mimeType, fileUri: imageGcsUri } };

        const req = { contents: [{ role: "user", parts: [{ text: prompt }, imageFilePart] }] };
        const result = await model.generateContent(req);

        const response = result.response;
        const rawAnalysis = response?.candidates?.[0]?.content?.parts?.[0]?.text;

        if (!rawAnalysis) {
            logger.error("A análise da IA (refeição) retornou vazia ou foi bloqueada.", { response });
            throw new HttpsError("unavailable", "Não foi possível analisar a imagem. Tente uma foto mais nítida.");
        }

        const jsonString = rawAnalysis.replace(/```json/g, "").replace(/```/g, "").trim();
        const parsedJson = JSON.parse(jsonString);

        logger.info(`Análise de refeição gerada para o usuário ${request.auth.uid}`);
        return parsedJson;

    } catch (error) {
        logger.error("Erro ao analisar a imagem da refeição:", error);
        if (error instanceof HttpsError) throw error;
        throw new HttpsError("internal", "Ocorreu um erro interno ao analisar sua refeição.");
    }
});

exports.gerarAnalisePreditiva = onCall({ cors: true, memory: "1GiB", timeoutSeconds: 300, minInstances: 0, enforceAppCheck: true }, async (request) => {
    if (!request.auth) {
        throw new HttpsError("unauthenticated", "A função deve ser chamada por um usuário autenticado.");
    }
    const { dependentId, symptoms, startDateString, endDateString, includeDoseHistory, includeHealthNotes, includeContinuousMeds } = request.data;
    if (!dependentId || !symptoms) {
        throw new HttpsError("invalid-argument", "Os parâmetros 'dependentId' e 'symptoms' são obrigatórios.");
    }
    const uid = request.auth.uid;
    const db = admin.firestore();
    try {
        const dependentDoc = await db.collection("dependentes").doc(dependentId).get();
        if (!dependentDoc.exists) {
            throw new HttpsError("not-found", "Dependente não encontrado.");
        }
        if (!dependentDoc.data()?.cuidadorIds?.includes(uid)) {
            throw new HttpsError("permission-denied", "Você não tem permissão para acessar os dados deste dependente.");
        }

        const dependent = dependentDoc.data();
        const startDate = new Date(startDateString);
        const endDate = new Date(endDateString);
        endDate.setHours(23, 59, 59, 999);

        const [
            medsSnapshot,
            dosesSnapshot,
            notesSnapshot,
            schedulesSnapshot,
            hydrationSnapshot,
            activitySnapshot,
            mealSnapshot,
            sleepSnapshot
        ] = await Promise.all([
            dependentDoc.ref.collection('medicamentos').get(),
            includeDoseHistory ? dependentDoc.ref.collection('historico_doses').where('timestamp', '>=', startDate).where('timestamp', '<=', endDate).get() : Promise.resolve(null),
            includeHealthNotes ? dependentDoc.ref.collection('health_notes').where('timestamp', '>=', startDate).where('timestamp', '<=', endDate).get() : Promise.resolve(null),
            dependentDoc.ref.collection('agendamentos').where('timestamp', '>=', startDate).where('timestamp', '<=', endDate).get(),
            dependentDoc.ref.collection('hidratacao').where('timestamp', '>=', startDate).where('timestamp', '<=', endDate).get(),
            dependentDoc.ref.collection('atividades_fisicas').where('timestamp', '>=', startDate).where('timestamp', '<=', endDate).get(),
            dependentDoc.ref.collection('refeicoes').where('timestamp', '>=', startDate).where('timestamp', '<=', endDate).get(),
            dependentDoc.ref.collection('sono_registros').where('data', '>=', startDateString).where('data', '<=', endDateString).get()
        ]);

        const allMeds = medsSnapshot.docs.map(doc => ({ id: doc.id, ...doc.data() }));
        const recentDoses = dosesSnapshot ? dosesSnapshot.docs.map(doc => doc.data()) : [];
        const recentNotes = notesSnapshot ? notesSnapshot.docs.map(doc => doc.data()) : [];
        const schedules = schedulesSnapshot.docs.map(doc => doc.data());
        const hydration = hydrationSnapshot.docs.map(doc => doc.data());
        const activities = activitySnapshot.docs.map(doc => doc.data());
        const meals = mealSnapshot.docs.map(doc => doc.data());
        const sleep = sleepSnapshot.docs.map(doc => doc.data());

        const prompt = buildAnalysisPrompt(
            dependent, symptoms, allMeds, recentDoses, recentNotes,
            schedules, hydration, activities, meals, sleep,
            startDate, endDate, includeDoseHistory, includeHealthNotes, includeContinuousMeds
        );

        const model = getGenerativeModel();
        const req = { contents: [{ role: "user", parts: [{ text: prompt }] }] };
        const result = await model.generateContent(req);
        const analysisText = result.response.candidates[0].content.parts[0].text;

        logger.info(`Análise Preditiva gerada para o dependente ${dependentId}`);
        return { analysis: analysisText };

    } catch (error) {
        logger.error(`Erro ao gerar análise preditiva para o dependente ${dependentId}:`, error);
        if (error instanceof HttpsError) {
            throw error;
        }
        throw new HttpsError("internal", "Ocorreu um erro interno ao gerar a análise.");
    }
});

exports.gerarResumoConsulta = onCall({ cors: true, memory: "1GiB", timeoutSeconds: 300, minInstances: 0, enforceAppCheck: true }, async (request) => {
    if (!request.auth) {
        throw new HttpsError("unauthenticated", "A função deve ser chamada por um usuário autenticado.");
    }
    const { dependentId, startDateString, endDateString } = request.data;
    if (!dependentId || !startDateString || !endDateString) {
        throw new HttpsError("invalid-argument", "Os argumentos 'dependentId', 'startDateString' e 'endDateString' são obrigatórios.");
    }
    const uid = request.auth.uid;
    const db = admin.firestore();
    try {
        const dependentDoc = await db.collection("dependentes").doc(dependentId).get();
        if (!dependentDoc.exists) {
            throw new HttpsError("not-found", "Dependente não encontrado.");
        }
        if (!dependentDoc.data()?.cuidadorIds?.includes(uid)) {
            throw new HttpsError("permission-denied", "Você não tem permissão para acessar os dados deste dependente.");
        }
        const dependent = dependentDoc.data();
        const startDate = new Date(startDateString);
        const endDate = new Date(endDateString);
        endDate.setHours(23, 59, 59, 999);
        const medsSnapshot = await dependentDoc.ref.collection('medicamentos').get();
        const scheduledMeds = medsSnapshot.docs.map(doc => doc.data()).filter(m => !m?.isUsoEsporadico);
        const dosesSnapshot = await dependentDoc.ref.collection('historico_doses').where('timestamp', '>=', startDate).where('timestamp', '<=', endDate).get();
        const recentDoses = dosesSnapshot.docs.map(doc => doc.data());
        const notesSnapshot = await dependentDoc.ref.collection('health_notes').where('timestamp', '>=', startDate).where('timestamp', '<=', endDate).get();
        const recentNotes = notesSnapshot.docs.map(doc => doc.data());
        const prompt = buildConsultationSummaryPrompt(dependent, scheduledMeds, recentDoses, recentNotes, startDate, endDate);
        const model = getGenerativeModel();
        const req = { contents: [{ role: "user", parts: [{ text: prompt }] }] };
        const result = await model.generateContent(req);
        const summaryText = result.response.candidates[0].content.parts[0].text;
        logger.info(`Resumo para consulta gerado para o dependente ${dependentId}`);
        return { summary: summaryText };
    } catch (error) {
        logger.error(`Erro ao gerar resumo para consulta para o dependente ${dependentId}:`, error);
        if (error instanceof HttpsError) {
            throw error;
        }
        throw new HttpsError("internal", "Ocorreu um erro interno ao gerar o resumo.");
    }
});

// ✅ OTIMIZAÇÃO: Memória reduzida para 512MiB
exports.getChatResponse = onCall({ cors: true, memory: "512MiB", minInstances: 0, enforceAppCheck: true }, async (request) => {
    if (!request.auth) throw new HttpsError("unauthenticated", "Usuário não autenticado.");
    const { prompt, dependentId } = request.data;
    if (!prompt || !dependentId) throw new HttpsError("invalid-argument", "Parâmetros 'prompt' e 'dependentId' são obrigatórios.");

    try {
        const db = admin.firestore();
        const now = new Date();
        const sevenDaysAgo = new Date();
        sevenDaysAgo.setDate(now.getDate() - 7);

        const [
            dependentDoc, chatHistorySnap, medsSnap, notesSnap, appointmentsSnap,
            hydrationSnap, activitySnap, sleepSnap, cycleSnap
        ] = await Promise.all([
            db.collection("dependentes").doc(dependentId).get(),
            db.collection("dependentes").doc(dependentId).collection("chat_history").orderBy("timestamp", "desc").limit(10).get(),
            db.collection("dependentes").doc(dependentId).collection("medicamentos").where("isPaused", "==", false).get(),
            db.collection("dependentes").doc(dependentId).collection("health_notes").where("timestamp", ">=", sevenDaysAgo).get(),
            db.collection("dependentes").doc(dependentId).collection("agendamentos").where("timestamp", ">=", admin.firestore.Timestamp.fromDate(now)).limit(5).get(),
            db.collection("dependentes").doc(dependentId).collection("hidratacao").where("timestamp", ">=", sevenDaysAgo).get(),
            db.collection("dependentes").doc(dependentId).collection("atividades_fisicas").where("timestamp", ">=", sevenDaysAgo).get(),
            db.collection("dependentes").doc(dependentId).collection("sono_registros").where("data", ">=", sevenDaysAgo.toISOString().split('T')[0]).get(),
            db.collection("dependentes").doc(dependentId).collection("daily_cycle_logs").orderBy("dateString", "desc").limit(45).get()
        ]);

        if (!dependentDoc.exists) throw new HttpsError("not-found", "Dependente não encontrado.");

        const dependentData = dependentDoc.data();
        const chatHistory = chatHistorySnap.docs.map(doc => doc.data()).reverse();
        const medications = medsSnap.docs.map(doc => doc.data());
        const healthNotes = notesSnap.docs.map(doc => doc.data());
        const appointments = appointmentsSnap.docs.map(doc => doc.data());

        const wellnessData = {};
        const hydrationTotal = hydrationSnap.docs.reduce((sum, doc) => sum + (doc.data().quantidadeMl || 0), 0);
        wellnessData.avgHydration = hydrationTotal / 7;
        const activityTotal = activitySnap.docs.reduce((sum, doc) => sum + (doc.data().duracaoMinutos || 0), 0);
        wellnessData.avgActivity = activityTotal / 7;
        const sleepTotal = sleepSnap.docs.reduce((sum, doc) => {
            try {
                const start = new Date(`1970-01-01T${doc.data().horaDeDormir}Z`);
                const end = new Date(`1970-01-01T${doc.data().horaDeAcordar}Z`);
                let diff = end.getTime() - start.getTime();
                if (diff < 0) diff += 24 * 60 * 60 * 1000;
                return sum + (diff / (1000 * 60 * 60));
            } catch (e) { return sum; }
        }, 0);
        wellnessData.avgSleep = sleepTotal / (sleepSnap.size || 1);
        wellnessData.cycleSummary = null;

        const model = getGenerativeModel();
        const fullPrompt = buildChatPrompt(prompt, dependentData, chatHistory, medications, healthNotes, appointments, wellnessData);

        const req = { contents: [{ role: "user", parts: [{ text: fullPrompt }] }] };
        const result = await model.generateContent(req);

        const responseText = result?.response?.candidates?.[0]?.content?.parts?.[0]?.text;
        if (!responseText) throw new HttpsError("unavailable", "O assistente não conseguiu processar sua pergunta.");

        return { response: responseText };

    } catch (error) {
        logger.error(`Erro no getChatResponse:`, error);
        if (error instanceof HttpsError) throw error;
        throw new HttpsError("internal", "Não foi possível conectar ao assistente de IA.");
    }
});

// ✅ OTIMIZAÇÃO: Memória reduzida para 512MiB
exports.sendEmergencyAlert = onCall({ cors: true, minInstances: 0, enforceAppCheck: true, memory: "512MiB" }, async (request) => {
    const { dependentId } = request.data;
    if (!dependentId) {
        throw new HttpsError("invalid-argument", "O ID do dependente é obrigatório.");
    }

    const db = admin.firestore();
    try {
        const dependentDoc = await db.collection("dependentes").doc(dependentId).get();
        if (!dependentDoc.exists) {
            throw new HttpsError("not-found", "Dependente não encontrado.");
        }

        const dependentData = dependentDoc.data();
        const dependentName = dependentData.nome || "Alguém";
        const cuidadorIds = dependentData.cuidadorIds || [];

        if (cuidadorIds.length === 0) {
            logger.warn(`Dependente ${dependentId} não possui cuidadores para notificar.`);
            return { success: true, message: "Nenhum cuidador para notificar." };
        }

        const payload = {
            notification: {
                title: "🚨ALERTA DE EMERGÊNCIA🚨",
                body: `${dependentName} precisa de ajuda! Toque para ver os detalhes.`
            },
            data: {
                type: "EMERGENCY_ALERT",
                dependentName: dependentName,
                dependentId: dependentId
            },
            android: {
                priority: "high"
            },
            apns: {
                payload: {
                    aps: {
                        sound: "default",
                        contentAvailable: true,
                    },
                },
                headers: {
                    "apns-push-type": "alert",
                    "apns-priority": "10",
                },
            },
        };

        await sendNotificationToCaregivers(cuidadorIds, payload);

        logger.info(`Alerta de emergência para ${dependentName} (ID: ${dependentId}) enviado para ${cuidadorIds.length} cuidadores.`);
        return { success: true, message: "Alerta enviado." };

    } catch (error) {
        logger.error(`Erro ao enviar alerta de emergência para o dependente ${dependentId}:`, error);
        if (error instanceof HttpsError) {
            throw error;
        }
        throw new HttpsError("internal", "Ocorreu um erro interno ao enviar o alerta.");
    }
});

// ✅ OTIMIZAÇÃO: Memória reduzida para 512MiB
exports.inviteCaregiverByEmail = onCall({ cors: true, minInstances: 0, enforceAppCheck: true, memory: "512MiB" }, async (request) => {
    if (!request.auth) {
        throw new HttpsError("unauthenticated", "Unauthenticated", "A função deve ser chamada por um usuário autenticado.");
    }
    const { email, dependenteId } = request.data;
    if (!email || !dependenteId) {
        throw new HttpsError("invalid-argument", "Invalid Arguments", "Os parâmetros 'email' e 'dependenteId' são obrigatórios.");
    }

    const db = admin.firestore();
    const inviterId = request.auth.uid;

    try {
        const [inviterDoc, dependentDoc] = await Promise.all([
            db.collection("users").doc(inviterId).get(),
            db.collection("dependentes").doc(dependenteId).get()
        ]);

        if (!inviterDoc.exists) throw new HttpsError("not-found", "Inviter Not Found", "Usuário remetente não encontrado.");
        if (!dependentDoc.exists) throw new HttpsError("not-found", "Dependent Not Found", "Dependente não encontrado.");

        const inviterData = inviterDoc.data();
        const dependentData = dependentDoc.data();

        if (email.toLowerCase() === inviterData.email_lowercase) {
            throw new HttpsError("invalid-argument", "Self Invite", "Você não pode convidar a si mesmo.");
        }

        const userToInviteQuery = await db.collection("users").where("email_lowercase", "==", email.toLowerCase()).limit(1).get();
        if (userToInviteQuery.empty) {
            throw new HttpsError("not-found", "User Not Found", "Nenhum cuidador encontrado com este e-mail.");
        }
        const userToInviteDoc = userToInviteQuery.docs[0];

        if (dependentData.cuidadorIds.includes(userToInviteDoc.id)) {
            throw new HttpsError("already-exists", "Already Caregiver", "Este cuidador já faz parte do círculo de cuidado.");
        }

        const existingInviteQuery = await db.collection("convites")
            .where("dependenteId", "==", dependenteId)
            .where("destinatarioEmail", "==", email.toLowerCase())
            .where("status", "==", "PENDENTE")
            .limit(1)
            .get();

        if (!existingInviteQuery.empty) {
            throw new HttpsError("already-exists", "Invite Exists", "Já existe um convite pendente para este e-mail e este dependente.");
        }

        const isPremium = inviterData.premium === true;
        if (!isPremium && dependentData.cuidadorIds.length >= 2) {
            throw new HttpsError("failed-precondition", "Limit Reached", "O plano gratuito permite apenas 2 cuidadores. Faça upgrade para adicionar mais.");
        }

        const newInvite = {
            dependenteId: dependenteId,
            dependenteNome: dependentData.nome,
            remetenteId: inviterId,
            remetenteNome: inviterData.name || "Cuidador",
            destinatarioEmail: email.toLowerCase(),
            status: "PENDENTE",
            createdAt: admin.firestore.FieldValue.serverTimestamp()
        };

        await db.collection("convites").add(newInvite);
        logger.info(`Convite enviado de ${inviterId} para ${email} pelo dependente ${dependenteId}`);
        return { success: true, message: "Convite enviado!" };

    } catch (error) {
        logger.error(`Erro ao enviar convite:`, error);
        if (error instanceof HttpsError) throw error;
        throw new HttpsError("internal", "Internal Error", "Ocorreu um erro interno ao processar o convite.");
    }
});

// ✅ OTIMIZAÇÃO: Memória reduzida para 256MiB
exports.notifyCaregiversOfScheduleChange = onCall({ cors: true, minInstances: 0, enforceAppCheck: true, memory: "256MiB" }, async (request) => {
    if (!request.auth) {
        throw new HttpsError("unauthenticated", "A função deve ser chamada por um usuário autenticado.");
    }

    const { dependentId, medicationName, newStartTime, actorName } = request.data;
    if (!dependentId || !medicationName || !newStartTime || !actorName) {
        throw new HttpsError("invalid-argument", "Parâmetros 'dependentId', 'medicationName', 'newStartTime' e 'actorName' são obrigatórios.");
    }

    const db = admin.firestore();

    try {
        const dependentDoc = await db.collection("dependentes").doc(dependentId).get();
        if (!dependentDoc.exists) {
            throw new HttpsError("not-found", "Dependente não encontrado.");
        }

        const dependentData = dependentDoc.data();
        const cuidadorIds = dependentData.cuidadorIds || [];

        const caregiversToNotify = cuidadorIds.filter(id => id !== request.auth.uid);

        if (caregiversToNotify.length === 0) {
            logger.info(`Nenhum outro cuidador para notificar sobre a mudança de horário do dependente ${dependentId}.`);
            return { success: true, message: " Nenhum outro cuidador para notificar." };
        }

        const payload = {
            notification: {
                title: "Tratamento Atualizado",
                body: `${actorName} reagendou os horários de ${medicationName} para ${dependentData.nome}, a começar às ${newStartTime}.`,
            },
            data: {
                dependentId: dependentId,
                type: "SCHEDULE_CHANGE",
            },
        };

        await sendNotificationToCaregivers(caregiversToNotify, payload);

        logger.info(`Notificação de reagendamento para ${medicationName} enviada para ${caregiversToNotify.length} cuidadores.`);
        return { success: true, message: "Cuidadores notificados." };

    } catch (error) {
        logger.error(`Erro ao notificar cuidadores sobre reagendamento para o dependente ${dependentId}:`, error);
        if (error instanceof HttpsError) throw error;
        throw new HttpsError("internal", "Ocorreu um erro interno ao enviar a notificação.");
    }
});

// ✅ OTIMIZAÇÃO: Memória reduzida para 512MiB
exports.acceptInvite = onCall({ cors: true, minInstances: 0, enforceAppCheck: true, memory: "512MiB" }, async (request) => {
    if (!request.auth) {
        throw new HttpsError("unauthenticated", "A função deve ser chamada por um usuário autenticado.");
    }
    const inviteId = request.data.inviteId;
    if (!inviteId) {
        throw new HttpsError("invalid-argument", "O ID do convite é obrigatório.");
    }
    const uid = request.auth.uid;
    const db = admin.firestore();
    const inviteRef = db.collection("convites").doc(inviteId);
    try {
        const [inviteDoc, userDoc] = await Promise.all([
            inviteRef.get(),
            db.collection("users").doc(uid).get()
        ]);
        if (!inviteDoc.exists) {
            throw new HttpsError("not-found", "Convite não encontrado.");
        }
        if (!userDoc.exists) {
            logger.error(`Usuário com UID ${uid} não encontrado no Firestore.`);
            throw new HttpsError("not-found", "Usuário não encontrado.");
        }
        const inviteData = inviteDoc.data();
        const userData = userDoc.data();
        if (inviteData?.destinatarioEmail?.toLowerCase() !== userData?.email?.toLowerCase()) {
            throw new HttpsError("permission-denied", "Você não tem permissão para aceitar este convite.");
        }
        if (inviteData?.status !== "PENDENTE") {
            throw new HttpsError("failed-precondition", "Este convite não está mais pendente.");
        }
        const dependentRef = db.collection("dependentes").doc(inviteData.dependenteId);
        await db.runTransaction(async (transaction) => {
            transaction.update(dependentRef, { cuidadorIds: admin.firestore.FieldValue.arrayUnion(uid) });
            transaction.update(inviteRef, { status: "ACEITO" });
        });
        logger.info(`Usuário ${uid} aceitou o convite ${inviteId} para o dependente ${inviteData.dependenteId}`);
        return { success: true, message: "Convite aceito com sucesso!" };
    } catch (error) {
        logger.error(`Erro ao aceitar convite ${inviteId} para o usuário ${uid}:`, error);
        if (error instanceof HttpsError) {
            throw error;
        }
        throw new HttpsError("internal", "Ocorreu um erro interno ao aceitar o convite.");
    }
});

// --- INÍCIO DAS FUNÇÕES DE PROMPT ---

function buildAnalysisPrompt(
    dependent, symptoms, allMeds, doses, notes, schedules,
    hydration, activities, meals, sleep,
    startDate, endDate, includeDoses, includeNotes, includeMeds
) {
    const dateFormatter = new Intl.DateTimeFormat('pt-BR', { timeZone: 'UTC', day: '2-digit', month: '2-digit', year: 'numeric' });
    const period = `${dateFormatter.format(startDate)} a ${dateFormatter.format(endDate)}`;

    const calculatedAge = calculateAgeFromDobString(dependent.dataDeNascimento);
    let profileString = `- Idade: ${calculatedAge !== null ? calculatedAge + " anos" : "Não informada"}\n`;
    if (dependent.sexo && dependent.sexo !== "NAO_INFORMADO") profileString += `- Sexo: ${dependent.sexo}\n`;
    if (dependent.peso) profileString += `- Peso: ${dependent.peso}kg\n`;
    if (dependent.altura) profileString += `- Altura: ${dependent.altura}cm\n`;
    if (dependent.tipoSanguineo && dependent.tipoSanguineo !== "NAO_SABE") profileString += `- Tipo Sanguíneo: ${dependent.tipoSanguineo}\n`;
    if (dependent.condicoesPreexistentes) profileString += `- Condições Pré-existentes: ${dependent.condicoesPreexistentes}\n`;
    if (dependent.alergias) profileString += `- Alergias: ${dependent.alergias}\n`;

    let prompt = `Você é um assistente de saúde virtual. Sua tarefa é analisar os dados do paciente "${dependent.nome}" para o período de ${period}. Responda APENAS com os tópicos solicitados. Se a informação for insuficiente, responda com 'Dados insuficientes para esta análise'.\n\n`;

    prompt += `**1. Perfil do Paciente:**\n${profileString}\n`;
    prompt += `**2. Sintomas e Observações do Cuidador:**\n- "${symptoms}"\n\n`;

    if (includeMeds) {
        prompt += `\n**3. Tratamentos Ativos:**\n`;
        const activeMeds = allMeds.filter(m => !m.isPaused);
        if (activeMeds.length > 0) {
            activeMeds.forEach(med => prompt += `- ${med.nome} (${med.dosagem})\n`);
        } else {
            prompt += `- Nenhum medicamento ativo.\n`;
        }
    }

    if (includeDoses) {
        prompt += `\n**4. Adesão e Histórico de Doses no Período:**\n`;
        const scheduledMeds = allMeds.filter(m => !m.isUsoEsporadico);
        if (scheduledMeds.length > 0) {
            scheduledMeds.forEach(med => {
                const dosesEsperadas = calculateExpectedDosesForMedication(med, startDate, endDate);
                const dosesTomadas = doses.filter(d => d.medicamentoId === med.id).length;
                const adesao = dosesEsperadas > 0 ? Math.round((dosesTomadas / dosesEsperadas) * 100) : 100;
                prompt += `- ${med.nome}: ${dosesTomadas} de ${dosesEsperadas} doses tomadas (${adesao}% de adesão).\n`;
            });
        } else {
            prompt += `- Nenhum medicamento de uso regular no período.\n`;
        }
    }

    if (includeNotes) {
        prompt += `\n**5. Anotações de Saúde no Período:**\n`;
        if (notes.length > 0) {
            notes.forEach(n => {
                const values = Object.entries(n.values).map(([key, value]) => `${key}: ${value}`).join(', ');
                prompt += `- ${n.timestamp.toDate().toLocaleString('pt-BR')}: ${n.type} - ${values}\n`;
            });
        } else {
            prompt += `- Nenhuma anotação de saúde registrada no período.\n`;
        }
    }

    prompt += `\n**6. Registros de Bem-Estar no Período:**\n`;
    if (hydration.length > 0) {
        prompt += `- Hidratação: ${hydration.length} registros.\n`;
    }
    if (activities.length > 0) {
        prompt += `- Atividade Física: ${activities.length} registros.\n`;
    }
    if (meals.length > 0) {
        prompt += `- Refeições: ${meals.length} registros.\n`;
    }
    if (sleep.length > 0) {
        prompt += `- Sono: ${sleep.length} registros.\n`;
    }
    if (hydration.length === 0 && activities.length === 0 && meals.length === 0 && sleep.length === 0) {
        prompt += `- Nenhum registro de bem-estar no período.\n`;
    }

    prompt += `\n**7. Agenda de Saúde no Período:**\n`;
    if (schedules.length > 0) {
        schedules.forEach(s => {
            prompt += `- ${s.timestamp.toDate().toLocaleString('pt-BR')}: ${s.titulo} (${s.tipo})\n`;
        });
    } else {
        prompt += `- Nenhum agendamento no período.\n`;
    }

    prompt += `\n**Análise Solicitada (formate a resposta exatamente com os seguintes títulos em negrito):**\n**Correlações:**\n**Interações Medicamentosas:**\n**Efeitos Colaterais:**\n**Nível de Urgência:**\n**Pontos para Discussão Médica:**\n**Observações Adicionais:**\n`;
    prompt += `\n**Importante:** Ao final, inclua a frase: 'Esta análise é gerada por inteligência artificial e não substitui uma consulta médica. Sempre consulte um profissional de saúde para diagnósticos e tratamentos.'`;
    return prompt;
}

function buildPrescriptionAnalysisPrompt(alergias, condicoes, medicamentosAtuais) {
    const safeAlergias = alergias || "Nenhuma";
    const safeCondicoes = condicoes || "Nenhuma";
    const safeMedicamentosAtuais = medicamentosAtuais && medicamentosAtuais.length > 0 ? medicamentosAtuais.join(', ') : "Nenhum";
    return `
        Sua tarefa é analisar a imagem de uma receita médica e extrair os medicamentos prescritos em um formato JSON estruturado.
        PRIMEIRO, extraia todo o texto da imagem.
        A PARTIR DO TEXTO EXTRAÍDO, para cada medicamento, extraia as seguintes informações:
        1. "nome": O nome completo do medicamento, INCLUINDO sua força/concentração (ex: "Amoxicilina 500mg").
        2. "dosagem": APENAS a quantidade a ser administrada por vez (ex: "1 comprimido", "15 gotas"). NUNCA inclua a força (mg, ml) neste campo.
        3. "posologia": O texto original completo das instruções de uso (ex: "1 comprimido a cada 8 horas por 7 dias").
        4. "frequenciaTipo": Classifique a frequência em "DIARIA", "SEMANAL", ou "INTERVALO_DIAS".
        5. "frequenciaValor": Um número. Para "DIARIA", é o número de vezes ao dia (ex: "8 em 8h" -> 3). Para "INTERVALO_DIAS", é o intervalo (ex: "dia sim, dia não" -> 2).
        6. "diasSemana": Se for "SEMANAL", um array de números (1=Seg, 7=Dom). Caso contrário, [].
        7. "duracaoDias": O número de dias do tratamento. Se não especificado, retorne 0.
        8. "isUsoContinuo": 'true' se o uso for contínuo ou a duração não for especificada.
        9. "isUsoEsporadico": 'true' se for "se necessário", "em caso de dor", etc.
        APÓS A EXTRAÇÃO, analise cada medicamento no contexto do perfil do paciente:
        - Alergias: ${safeAlergias}
        - Condições Pré-existentes: ${safeCondicoes}
        - Medicamentos em Uso: ${safeMedicamentosAtuais}
        Com base nisso, preencha o campo "avisos" com uma lista de strings contendo alertas críticos. Se não houver avisos, retorne uma lista vazia.
        Formate a resposta final como um ÚNICO objeto JSON com a chave "medications".
        Se nenhum medicamento for identificado, retorne: {"medications": []}.
    `;
}

function parsePrescriptionAnalysis(rawAnalysis) {
    if (!rawAnalysis || rawAnalysis.trim() === "") {
        logger.error("A análise da IA retornou um valor nulo ou vazio.");
        return { medications: [] };
    }
    try {
        const jsonString = rawAnalysis.replace(/```json/g, "").replace(/```/g, "").trim();
        const parsedJson = JSON.parse(jsonString);
        if (!parsedJson.medications || !Array.isArray(parsedJson.medications)) {
            logger.error("A resposta da IA não contém um array 'medications' válido.", parsedJson);
            return { medications: [] };
        }
        return parsedJson;
    } catch (e) {
        logger.error("Erro ao analisar o JSON da IA", { error: e, rawText: rawAnalysis });
        return { medications: [] };
    }
}

function buildMealAnalysisPrompt(healthProfile) {
    const age = calculateAgeFromDobString(healthProfile.idade);
    let prompt = `
        Você é um nutricionista virtual. Analise a imagem desta refeição e retorne APENAS um objeto JSON.

        Considere o perfil de saúde:
        - Idade: ${age || 'N/A'}
        - Peso: ${healthProfile.peso || 'N/A'} kg
        - Altura: ${healthProfile.altura || 'N/A'} cm
        - Sexo: ${healthProfile.sexo || 'N/A'}

        Formato JSON esperado:
        {
          "descricao": "Uma breve descrição dos alimentos visíveis (ex: 'Prato com arroz, feijão, filé de frango grelhado e salada de alface e tomate.')",
          "caloriasEstimadas": "Um NÚMERO inteiro estimado de calorias (ex: 550)",
          "analiseSaude": "Uma análise curta (2-3 frases) sobre o quão saudável esta refeição parece ser, considerando o perfil. Seja encorajador.",
          "sugestao": "Uma sugestão simples de melhoria ou um elogio (ex: 'Ótima fonte de proteína! Tente adicionar mais vegetais verdes na próxima vez.' ou 'Refeição muito bem balanceada!')"
        }

        REGRAS: Retorne APENAS o JSON. Não inclua \`\`\`json\`\`\`.
    `;
    return prompt;
}


// --- INÍCIO DAS FUNÇÕES AGENDADAS (ON SCHEDULE) ---

// (A função onDataWritten que estava faltando está aqui)
exports.onDataWritten = onDocumentWritten({
    document: "dependentes/{dependentId}/{collectionId}/{docId}",
    minInstances: 0
}, async (event) => {
    const collectionId = event.params.collectionId;
    const dependentId = event.params.dependentId;
    const docId = event.params.docId;

    const relevantCollections = [
        "historico_doses", "health_notes", "atividades", "insights",
        "hidratacao", "atividades_fisicas", "refeicoes", "sono_registros"
    ];

    if (!relevantCollections.includes(collectionId)) {
        return;
    }

    const db = admin.firestore();
    const timelineRef = db.collection("dependentes").doc(dependentId).collection("timeline").doc(`${collectionId}_${docId}`);

    if (!event.data.after.exists) {
        try {
            await timelineRef.delete();
            logger.info(`[Timeline] Evento ${timelineRef.path} excluído.`);
        } catch (error) {
            logger.error(`[Timeline] Erro ao excluir evento ${timelineRef.path}:`, error);
        }
        return;
    }

    const dataBefore = event.data.before ? event.data.before.data() : null;
    const dataAfter = event.data.after.data();

    if (dataBefore && dataBefore.timestampString === dataAfter.timestampString) {
        logger.info(`[Timeline] Gatilho ignorado para ${timelineRef.path} pois o timestamp não mudou.`);
        return;
    }

    const eventData = await createTimelineEvent(dependentId, collectionId, docId, dataAfter);

    if (eventData) {
        try {
            await timelineRef.set(eventData);
            logger.info(`[Timeline] Evento salvo em ${timelineRef.path}`);
        } catch (error) {
            logger.error(`[Timeline] Erro ao salvar evento em ${timelineRef.path}:`, error);
        }
    }
});

// (A função handlePlaySubscriptionNotification que estava faltando está aqui)
exports.handlePlaySubscriptionNotification = onMessagePublished(
    { topic: "play-billing", minInstances: 0 },
    async (event) => {
        await initGooglePlayPublisher();

        logger.info("📩 Notificação do Google Play recebida via Pub/Sub.");

        if (!event?.data?.message?.data) {
            logger.warn("Evento Pub/Sub recebido sem dados válidos. Estrutura incorreta ou vazia.");
            return;
        }
        const data = Buffer.from(event.data.message.data, "base64").toString("utf8");
        let notification;

        try {
            notification = JSON.parse(data);
        } catch (parseError) {
            logger.error("Falha ao interpretar JSON da notificação:", parseError);
            return;
        }

        if (!notification.subscriptionNotification) {
            logger.info("Notificação não relacionada a assinatura. Ignorada.");
            return;
        }

        const { purchaseToken, notificationType } = notification.subscriptionNotification;

        logger.info("Detalhes da notificação de assinatura recebida:", {
            notificationType,
            purchaseToken,
        });

        if (!purchaseToken) {
            logger.error("Notificação recebida sem purchaseToken. Ignorando.");
            return;
        }

        const db = admin.firestore();

        const purchasesSnapshot = await db
            .collectionGroup("purchases")
            .where("purchaseToken", "==", purchaseToken)
            .limit(1)
            .get();

        if (purchasesSnapshot.empty) {
            logger.error(`Nenhum usuário encontrado para o purchaseToken: ${purchaseToken}. Ignorando.`);
            return;
        }

        const purchaseDocRef = purchasesSnapshot.docs[0].ref;
        const purchaseData = purchasesSnapshot.docs[0].data();
        const ownerId = purchaseData.userId;

        if (!ownerId) {
            logger.error(`Documento de compra sem userId associado. Token: ${purchaseToken}. Ignorando.`);
            return;
        }

        const ownerRef = db.collection("users").doc(ownerId);

        if (purchaseData.lastNotificationTimestamp?.seconds >= admin.firestore.Timestamp.now().seconds) {
            logger.info(`Evento duplicado recebido para o token ${purchaseToken}. Ignorando.`);
            return;
        }

        let subscriptionDetails;
        try {
            const subscriptionResponse = await publisher.purchases.subscriptionsv2.get({
                packageName: ANDROID_PACKAGE_NAME,
                token: purchaseToken,
            });
            subscriptionDetails = subscriptionResponse.data;
        } catch (apiError) {
            logger.error(
                `❌ Erro ao consultar a API do Google Play para o token ${purchaseToken}:`,
                apiError
            );
            throw new Error(`Falha na consulta da API do Google Play: ${apiError.message}`);
        }

        const subscriptionState = subscriptionDetails.subscriptionState;
        const isPremium = subscriptionState === "SUBSCRIPTION_STATE_ACTIVE";

        const expiryTimeMillisString = subscriptionDetails.expiryTime;
        let expiryTimestamp = null;

        if (expiryTimeMillisString) {
            const expiryMillis = parseInt(expiryTimeMillisString, 10);
            if (!isNaN(expiryMillis)) {
                expiryTimestamp = admin.firestore.Timestamp.fromMillis(expiryMillis);
            }
        }

        logger.info(
            `Atualizando status premium do usuário ${ownerId} → ${
            isPremium ? "ATIVO" : "INATIVO"
            } (API: ${subscriptionState}). Data de expiração: ${expiryTimestamp ? expiryTimestamp.toDate().toISOString() : 'N/A'}`
        );

        const updateData = {
            premium: isPremium,
            subscriptionExpiryDate: expiryTimestamp,
            familyId: null,
            updatedAt: admin.firestore.FieldValue.serverTimestamp(),
        };

        const batch = db.batch();
        batch.update(ownerRef, updateData);
        batch.update(purchaseDocRef, {
            lastNotificationTimestamp: admin.firestore.FieldValue.serverTimestamp(),
        });

        try {
            await batch.commit();
            logger.info(`✅ Status do usuário ${ownerId} atualizado com sucesso. Token: ${purchaseToken}`);
        } catch (batchError) {
            logger.error(
                `❌ Erro ao comitar o batch de atualização para o usuário ${ownerId}:`,
                batchError
            );
            throw batchError;
        }
    }
);

// (A função verifyExpiredSubscriptions que estava faltando está aqui)
exports.verifyExpiredSubscriptions = onSchedule({
    schedule: "every day 18:00",
    timeZone: "America/Sao_Paulo",
    minInstances: 0
}, async (event) => {
    await initGooglePlayPublisher();

    logger.log("Iniciando verificação diária de status de assinaturas...");
    const db = admin.firestore();

    try {
        const premiumUsersQuery = await db.collection("users")
            .where("premium", "==", true)
            .get();

        if (premiumUsersQuery.empty) {
            logger.log("Nenhum usuário premium para verificar. Trabalho concluído.");
            return;
        }

        logger.info(`Encontrados ${premiumUsersQuery.size} usuários premium para verificar.`);
        const batch = db.batch();
        let usersToDeactivate = 0;

        for (const userDoc of premiumUsersQuery.docs) {
            const userId = userDoc.id;
            const userData = userDoc.data();

            const purchasesSnapshot = await db.collection("users").doc(userId).collection("purchases").limit(1).get();

            if (purchasesSnapshot.empty) {
                logger.warn(`Usuário ${userId} é premium mas não tem documento de compra. Revertendo para não-premium.`);
                batch.update(userDoc.ref, { premium: false, familyId: null });
                usersToDeactivate++;
                continue;
            }

            const purchaseToken = purchasesSnapshot.docs[0].data()?.purchaseToken;

            if (!purchaseToken) {
                logger.warn(`Documento de compra para o usuário ${userId} não tem purchaseToken. Revertendo.`);
                batch.update(userDoc.ref, { premium: false, familyId: null });
                usersToDeactivate++;
                continue;
            }

            try {
                const subscriptionResponse = await publisher.purchases.subscriptionsv2.get({
                    packageName: ANDROID_PACKAGE_NAME,
                    token: purchaseToken,
                });

                const subscriptionState = subscriptionResponse.data.subscriptionState;
                const isStillActive = subscriptionState === "SUBSCRIPTION_STATE_ACTIVE";

                if (!isStillActive) {
                    logger.info(`Assinatura do usuário ${userId} expirou (Status API: ${subscriptionState}). Agendando desativação.`);
                    batch.update(userDoc.ref, { premium: false, familyId: null });
                    usersToDeactivate++;
                }

            } catch (apiError) {
                if (apiError.code === 410 || apiError.code === 404) {
                     logger.warn(`Assinatura para o token ${purchaseToken} (usuário ${userId}) não foi encontrada na API. Desativando premium.`);
                     batch.update(userDoc.ref, { premium: false, familyId: null });
                     usersToDeactivate++;
                } else {
                    logger.error(`Erro ao consultar a API do Google Play para o usuário ${userId}:`, apiError.message);
                }
            }
        }

        if (usersToDeactivate > 0) {
            await batch.commit();
            logger.info(`${usersToDeactivate} usuários foram atualizados para premium: false.`);
        } else {
            logger.log("Nenhuma alteração de status necessária para os usuários premium verificados.");
        }

    } catch (error) {
        logger.error("Erro catastrófico ao verificar e corrigir assinaturas expiradas:", error);
    }
});

exports.checkLowStock = onDocumentUpdated({
    document: "dependentes/{dependentId}/medicamentos/{medicamentoId}",
    minInstances: 0
}, async (event) => {
    const change = event.data;
    if (!change) { return; }

    const newData = change.after.data();
    const oldData = change.before.data();

    if (!newData || !oldData) return;

    const newStockTotal = newData.estoqueAtualTotal ?? 0;
    const oldStockTotal = oldData.estoqueAtualTotal ?? 0;
    const alertLevel = newData.nivelDeAlertaEstoque ?? 0;

    const shouldSendAlert = newStockTotal < oldStockTotal &&
        alertLevel > 0 &&
        newStockTotal <= alertLevel &&
        !newData.alertaDeEstoqueEnviado;

    if (shouldSendAlert) {
        logger.info(`Condições de alerta de estoque baixo atendidas para o medicamento: ${newData.nome}`);

        const dependentDoc = await admin.firestore().collection("dependentes").doc(event.params.dependentId).get();
        const cuidadorIds = dependentDoc.data()?.cuidadorIds || [];

        if (cuidadorIds.length > 0) {
            const caregiversToNotify = await getCaregiversToNotify(cuidadorIds, 'lowStockAlertsEnabled');

            if (caregiversToNotify.length > 0) {
                const payload = {
                    notification: {
                        title: `Estoque Baixo: ${newData.nome}`,
                        body: `Restam apenas ${newStockTotal} ${newData.unidadeDeEstoque} para ${dependentDoc.data()?.nome}. Planeje a reposição.`,
                    },
                    data: {
                        dependentId: event.params.dependentId,
                        type: "LOW_STOCK_ALERT",
                        medicationId: event.params.medicamentoId
                    }
                };
                await sendNotificationToCaregivers(caregiversToNotify, payload);
                await change.after.ref.update({ alertaDeEstoqueEnviado: true });
                logger.info(`Alerta de estoque baixo enviado para ${newData.nome}.`);
            } else {
                logger.info(`Nenhum cuidador com alertas de estoque ativados para ${newData.nome}.`);
            }
        }
        return;
    }

    const shouldResetAlert = newStockTotal > oldStockTotal &&
        newStockTotal > alertLevel &&
        newData.alertaDeEstoqueEnviado;

    if (shouldResetAlert) {
        logger.info(`Estoque de ${newData.nome} foi reposto. Resetando flag de alerta.`);
        await change.after.ref.update({ alertaDeEstoqueEnviado: false });
    }
});


exports.checkMissedDoses = onSchedule({
    schedule: "every 4 hours", // ✅ OTIMIZAÇÃO DE CPU
    minInstances: 0
}, async (event) => {
    logger.log("Executando verificação de doses atrasadas...");
    const now = new Date();
    const delayThreshold = 31 * 60 * 1000;
    try {
        const dependentsSnapshot = await admin.firestore().collection('dependentes').get();
        for (const dependentDoc of dependentsSnapshot.docs) {
            const dependent = dependentDoc.data();
            const medsSnapshot = await dependentDoc.ref.collection('medicamentos').where("isPaused", "==", false).where("isUsoEsporadico", "==", false).get();

            for (const medDoc of medsSnapshot.docs) {
                const med = medDoc.data();
                if (!med?.usaNotificacao || !med?.horarios || med.horarios.length === 0) continue;

                const lastDoseSnapshot = await dependentDoc.ref.collection('historico_doses')
                    .where('medicamentoId', '==', medDoc.id)
                    .orderBy('timestamp', 'desc')
                    .limit(1)
                    .get();

                const lastDoseData = lastDoseSnapshot.docs[0]?.data();
                const lastDoseTime = lastDoseData ? lastDoseData.timestamp.toDate() : null;
                const nextDoseTime = calculateNextDoseTimeJS(med);

                if (med.missedDoseAlertSent && lastDoseTime && nextDoseTime && lastDoseTime > nextDoseTime) {
                    await medDoc.ref.update({ missedDoseAlertSent: false });
                    continue;
                }

                if (nextDoseTime && nextDoseTime < now && (now.getTime() - nextDoseTime.getTime()) > delayThreshold) {
                    if (!med?.missedDoseAlertSent) {
                        const cuidadorIds = dependent?.cuidadorIds || [];

                        const caregiversToNotify = await getCaregiversToNotify(cuidadorIds, 'missedDoseAlertsEnabled');

                        if (caregiversToNotify.length > 0) {
                            const payload = {
                                notification: {
                                    title: 'Lembrete de Cuidado',
                                    body: `Percebemos que uma dose de ${med.nome} para ${dependent.nome} está atrasada. Por favor, verifique.`
                                },
                                data: {
                                    dependentId: dependentDoc.id,
                                    type: "MISSED_DOSE_ALERT",
                                    medicationId: medDoc.id
                                }
                            };
                            await sendNotificationToCaregivers(caregiversToNotify, payload);
                            await medDoc.ref.update({ missedDoseAlertSent: true });
                        }
                    }
                }
            }
        }
        logger.log("Verificação de doses atrasadas concluída.");
    } catch (error) {
        logger.error("Erro ao verificar doses atrasadas:", error);
    }
});



exports.checkUpcomingSchedules = onSchedule({
    schedule: "every day 05:00",
    timeZone: "America/Sao_Paulo",
    minInstances: 0
}, async (event) => {
    logger.log("Executando verificação de agendamentos para o dia seguinte...");
    const tomorrow = new Date();
    tomorrow.setDate(tomorrow.getDate() + 1);
    const startOfTomorrow = new Date(tomorrow.setHours(0, 0, 0, 0));
    const endOfTomorrow = new Date(tomorrow.setHours(23, 59, 59, 999));
    try {
        const dependentsSnapshot = await admin.firestore().collection('dependentes').get();
        for (const dependentDoc of dependentsSnapshot.docs) {
            const dependent = dependentDoc.data();
            const schedulesSnapshot = await dependentDoc.ref.collection('agendamentos')
                .where('timestamp', '>=', startOfTomorrow)
                .where('timestamp', '<=', endOfTomorrow)
                .get();

            if (schedulesSnapshot.empty) continue;

            const cuidadorIds = dependent?.cuidadorIds || [];
            const caregiversToNotify = await getCaregiversToNotify(cuidadorIds, 'appointmentRemindersEnabled');

            if (caregiversToNotify.length === 0) continue;

            for (const scheduleDoc of schedulesSnapshot.docs) {
                const schedule = scheduleDoc.data();
                const scheduleTime = schedule?.timestamp?.toDate();
                if (scheduleTime) {
                    const timeFormatter = new Intl.DateTimeFormat('pt-BR', { timeZone: 'America/Sao_Paulo', hour: '2-digit', minute: '2-digit' });
                    const formattedTime = timeFormatter.format(scheduleTime);
                    const payload = {
                        notification: {
                            title: `Agenda de ${dependent.nome}`,
                            body: `Lembrete para amanhã, às ${formattedTime}: ${schedule.titulo}.`,
                        },
                        data: {
                            dependentId: dependentDoc.id,
                            type: "SCHEDULE_REMINDER"
                        }
                    };
                    await sendNotificationToCaregivers(caregiversToNotify, payload);
                }
            }
        }
        logger.log("Verificação de agendamentos concluída.");
    } catch (error) {
        logger.error("Erro ao verificar agendamentos:", error);
    }
});



exports.notifyOnInviteAccepted = onDocumentUpdated({
    document: "convites/{inviteId}",
    minInstances: 0
}, async (event) => {
    const change = event.data;
    if (!change) { return; }
    const newData = change.after.data();
    const oldData = change.before.data();
    if (oldData?.status === "PENDENTE" && newData?.status === "ACEITO") {
        logger.info(`Convite ${event.params.inviteId} foi aceito.`);
        const remetenteId = newData.remetenteId;
        const dependenteNome = newData.dependenteNome;
        const userSnapshot = await admin.firestore().collection("users")
            .where("email", "==", newData.destinatarioEmail).limit(1).get();
        if (userSnapshot.empty) {
            logger.warn(`Não foi possível encontrar o usuário com e-mail ${newData.destinatarioEmail}`);
            return;
        }
        const novoCuidadorNome = userSnapshot.docs[0].data()?.name || "Um novo cuidador";
        const payload = {
            notification: {
                title: "Círculo de Cuidado Atualizado",
                body: `${novoCuidadorNome} aceitou seu convite para cuidar de ${dependenteNome}.`,
            },
        };
        await sendNotificationToCaregivers([remetenteId], payload);
    }
});

exports.generateProactiveInsights = onSchedule({ schedule: "every day 22:00", timeZone: "America/Sao_Paulo", minInstances: 0 }, async (event) => {
    logger.log("Iniciando a geração de insights proativos diários...");
    try {
        const model = getGenerativeModel();
        const dependentsSnapshot = await admin.firestore().collection('dependentes').get();

        for (const dependentDoc of dependentsSnapshot.docs) {
            const dependent = dependentDoc.data();
            const cuidadorIds = dependent?.cuidadorIds || [];
            if (cuidadorIds.length === 0) continue;

            const usersSnapshot = await admin.firestore().collection("users").where(admin.firestore.FieldPath.documentId(), "in", cuidadorIds).get();
            const isPremiumTier = usersSnapshot.docs.some(doc => doc.data()?.premium === true);
            if (!isPremiumTier) continue;

            const endDate = new Date();
            const startDate = new Date();
            startDate.setDate(endDate.getDate() - 7);
            const startDateString = startDate.toISOString().split('T')[0];

            const [
                medsSnapshot, dosesSnapshot, notesSnapshot, hydrationSnapshot,
                activitySnapshot, mealSnapshot, sleepSnapshot, cycleSnapshot
            ] = await Promise.all([
                dependentDoc.ref.collection('medicamentos').get(),
                dependentDoc.ref.collection('historico_doses').where('timestamp', '>=', startDate).get(),
                dependentDoc.ref.collection('health_notes').where('timestamp', '>=', startDate).get(),
                dependentDoc.ref.collection('hidratacao').where('timestamp', '>=', startDate).get(),
                dependentDoc.ref.collection('atividades_fisicas').where('timestamp', '>=', startDate).get(),
                dependentDoc.ref.collection('refeicoes').where('timestamp', '>=', startDate).get(),
                dependentDoc.ref.collection('sono_registros').where('data', '>=', startDateString).get(),
                dependentDoc.ref.collection('daily_cycle_logs').where('dateString', '>=', startDateString).get(),
            ]);

            const allMeds = medsSnapshot.docs.map(doc => doc.data());
            const recentDoses = dosesSnapshot.docs.map(doc => doc.data());
            const recentNotes = notesSnapshot.docs.map(doc => doc.data());
            const hydration = hydrationSnapshot.docs.map(doc => doc.data());
            const activities = activitySnapshot.docs.map(doc => doc.data());
            const meals = mealSnapshot.docs.map(doc => doc.data());
            const sleep = sleepSnapshot.docs.map(doc => doc.data());
            const cycleLogs = cycleSnapshot.docs.map(doc => doc.data());

            if (recentDoses.length === 0 && recentNotes.length === 0 && activities.length === 0 && sleep.length === 0 && cycleLogs.length === 0) {
                continue;
            }

            const prompt = buildInsightPrompt(dependent, allMeds, recentDoses, recentNotes, hydration, activities, meals, sleep, cycleLogs);

            const req = { contents: [{ role: "user", parts: [{ text: prompt }] }] };
            const result = await model.generateContent(req);
            const rawResponse = result.response.candidates[0].content.parts[0].text;
            const insights = parseInsights(rawResponse);

            if (insights.length > 0) {
                const batch = admin.firestore().batch();
                const insightsCollection = dependentDoc.ref.collection("insights");
                insights.forEach(insight => {
                    const newInsightRef = insightsCollection.doc();
                    batch.set(newInsightRef, {
                        ...insight,
                        timestamp: admin.firestore.FieldValue.serverTimestamp(),
                        isRead: false,
                    });
                });
                await batch.commit();
                logger.info(`${insights.length} novos insights salvos para o dependente ${dependentDoc.id}`);
            }
        }
        logger.log("Geração de insights diários concluída.");
    } catch (error) {
        logger.error("Erro catastrófico ao gerar insights diários:", error);
    }
});

function buildInsightPrompt(dependent, meds, doses, notes, hydration, activities, meals, sleep, cycleLogs) {
    let prompt = `
        Você é um analista de dados de saúde assistente. Sua tarefa é analisar os dados de saúde dos últimos 7 dias para o paciente "${dependent.nome}" e identificar até 3 padrões, tendências ou correlações notáveis. Foque em reforço positivo, alertas de adesão e correlações entre estilo de vida, ciclo menstrual e sintomas.

        REGRAS CRÍTICAS: NÃO FAÇA DIAGNÓSTICOS. NÃO DÊ CONSELHOS MÉDICOS. Formate a resposta como um array JSON com objetos contendo "type", "title", e "description" (máximo 30 palavras).
        Tipos de "type" permitidos: "POSITIVE_REINFORCEMENT", "ADHERENCE_ISSUE", "HEALTH_TREND_ALERT", "CORRELATION_INSIGHT".

        DADOS PARA ANÁLISE (ÚLTIMOS 7 DIAS):
        - Perfil: Alergias (${dependent.alergias || "N/A"}), Condições (${dependent.condicoesPreexistentes || "N/A"}).
        - Anotações de Saúde: ${notes.length} registros.
        - Hidratação: ${hydration.length} registros.
        - Atividade Física: ${activities.length} registros.
        - Refeições: ${meals.length} registros.
        - Sono: ${sleep.length} registros.
        - Ciclo Menstrual: ${cycleLogs.length} registros diários.

        EXEMPLO DE SAÍDA JSON:
        \`\`\`json
        [
          {
            "type": "CORRELATION_INSIGHT",
            "title": "Sono e Humor",
            "description": "Observamos que nos dias com menos de 6 horas de sono, você tendeu a registrar o humor como 'Irritada'."
          }
        ]
        \`\`\`
        Agora, analise os dados e gere o JSON.
    `;
    return prompt;
}

function parseInsights(rawResponse) {
    if (!rawResponse || rawResponse.trim() === "") {
        logger.warn("A IA de insights retornou uma resposta vazia.");
        return [];
    }
    try {
        const jsonString = rawResponse.replace(/```json/g, "").replace(/```/g, "").trim();
        const parsed = JSON.parse(jsonString);
        if (Array.isArray(parsed)) {
            return parsed.filter(item => item.type && item.title && item.description);
        }
        return [];
    } catch (e) {
        logger.error("Erro ao parsear o JSON de insights da IA", { error: e, rawText: rawResponse });
        return [];
    }
}

exports.checkUpcomingExpiries = onSchedule({
    schedule: "every day 04:00",
    timeZone: "America/Sao_Paulo",
    minInstances: 0
}, async (event) => {
    logger.log("Executando verificação diária de validade de lotes...");
    const today = new Date();
    const thirtyDaysFromNow = new Date();
    thirtyDaysFromNow.setDate(today.getDate() + 30);
    try {
        const dependentsSnapshot = await admin.firestore().collection('dependentes').get();
        for (const dependentDoc of dependentsSnapshot.docs) {
            const dependent = dependentDoc.data();
            const medsSnapshot = await dependentDoc.ref.collection('medicamentos').get();

            for (const medDoc of medsSnapshot.docs) {
                const med = medDoc.data();
                if (!med.lotes || med.lotes.length === 0) continue;

                let needsUpdate = false;
                const cuidadorIds = dependent.cuidadorIds || [];
                const caregiversToNotify = await getCaregiversToNotify(cuidadorIds, 'expiryAlertsEnabled');

                const updatedLotes = med.lotes.map(lote => {
                    const expiryDate = new Date(lote.dataValidadeString);
                    if (!lote.alertaValidadeEnviado && expiryDate >= today && expiryDate <= thirtyDaysFromNow) {
                        if (caregiversToNotify.length > 0) {
                            const title = `Validade Próxima: ${med.nome}`;
                            const body = `O lote de ${med.nome} (${lote.quantidade} ${med.unidadeDeEstoque}) para ${dependent.nome} vence em ${expiryDate.toLocaleDateString('pt-BR')}.`;
                            sendNotificationToCaregivers(caregiversToNotify, { notification: { title, body } });
                        }
                        needsUpdate = true;
                        return { ...lote, alertaValidadeEnviado: true };
                    }
                    return lote;
                });

                if (needsUpdate) {
                    await medDoc.ref.update({ lotes: updatedLotes });
                }
            }
        }
        logger.log("Verificação de validade concluída.");
    } catch (error) {
        logger.error("Erro ao verificar validades:", error);
    }
});


exports.sendDailySummary = onSchedule({
    schedule: "every day 08:00", // ✅ OTIMIZAÇÃO DE CPU
    timeZone: "America/Sao_Paulo",
    minInstances: 0
}, async (event) => {

    logger.log(`Executando Resumo Diário agendado para as 08:00.`);
    try {
        const usersSnapshot = await admin.firestore().collection("users")
            .where("premium", "==", true)
            .where("dailySummaryEnabled", "==", true)
            .get();
        if (usersSnapshot.empty) {
            logger.log("Nenhum usuário para notificar.");
            return;
        }
        for (const userDoc of usersSnapshot.docs) {
            const user = userDoc.data();
            const dependentsSnapshot = await admin.firestore().collection("dependentes")
                .where("cuidadorIds", "array-contains", userDoc.id)
                .get();
            if (dependentsSnapshot.empty) continue;
            let summaryBody = "";
            let totalDosesDoDia = 0;
            for (const dependentDoc of dependentsSnapshot.docs) {
                const dependent = dependentDoc.data();
                const meds = (await dependentDoc.ref.collection('medicamentos').get()).docs.map(d => d.data());
                const today = new Date();
                const activeMeds = meds.filter(m => !m.isUsoEsporadico && !m.isArchived);
                const dosesEsperadasHoje = calculateExpectedDosesForPeriod(activeMeds, today, today);
                if (dosesEsperadasHoje > 0) {
                    summaryBody += `\n• ${dependent.nome}: ${dosesEsperadasHoje} dose(s) agendada(s) hoje.`;
                    totalDosesDoDia += dosesEsperadasHoje;
                }
            }
            if (totalDosesDoDia === 0) {
                summaryBody = "Nenhuma dose agendada para hoje. Tenha um ótimo dia!";
            } else {
                summaryBody = `Hoje há ${totalDosesDoDia} dose(s) no total para seus dependentes.` + summaryBody;
            }
            if (summaryBody.trim() === "") continue;
            const payload = {
                notification: {
                    title: "Seu Resumo Diário NidusCare ☀️",
                    body: summaryBody.trim(),
                },
                data: {
                    type: "DAILY_SUMMARY"
                }
            };
            await sendNotificationToCaregivers([userDoc.id], payload);
        }
    } catch (error) {
        logger.error("Erro ao gerar resumo diário:", error);
    }
});

exports.onDependentDeleted = onDocumentWritten({ document: "dependentes/{dependentId}", minInstances: 0 }, async (event) => {
    if (event.data.after.exists) {
        return;
    }

    const snapshot = event.data.before;
    const dependentId = event.params.dependentId;

    logger.info(`[Exclusão] Gatilho disparado para o dependente: ${dependentId}. Iniciando limpeza...`);

    const db = admin.firestore();
    const bucket = admin.storage().bucket();

    const subcollections = [
        "medicamentos", "historico_doses", "reminders", "health_notes",
        "documentos_saude", "agendamentos", "atividades", "insights",
        "analysis_history", "chat_history", "vacinas", "sono_registros",
        "refeicoes", "atividades_fisicas"
    ];

    const deletionPromises = subcollections.map(async (collectionName) => {
        try {
            const collectionRef = db.collection("dependentes").doc(dependentId).collection(collectionName);
            const snapshot = await collectionRef.limit(500).get();

            if (snapshot.empty) {
                return { status: 'fulfilled', collection: collectionName, count: 0 };
            }

            if (collectionName === 'documentos_saude') {
                for (const doc of snapshot.docs) {
                    const fileUrl = doc.data().fileUrl;
                    if (fileUrl) {
                        try {
                            const decodedUrl = decodeURIComponent(fileUrl.split('?')[0]);
                            const filePath = decodedUrl.substring(decodedUrl.indexOf('/o/') + 3);
                            await bucket.file(filePath).delete();
                            logger.info(`[Exclusão] Arquivo do Storage excluído: ${filePath}`);
                        } catch (e) {
                            logger.error(`[Exclusão] Falha ao excluir arquivo do Storage: ${fileUrl}`, e);
                        }
                    }
                }
            }

            const batch = db.batch();
            snapshot.docs.forEach(doc => {
                batch.delete(doc.ref);
            });
            await batch.commit();
            return { status: 'fulfilled', collection: collectionName, count: snapshot.size };
        } catch (error) {
            return { status: 'rejected', collection: collectionName, reason: error.message };
        }
    });

    const results = await Promise.allSettled(deletionPromises);

    results.forEach(result => {
        if (result.status === 'fulfilled' && result.value.count > 0) {
            logger.info(`[Exclusão] Sucesso ao limpar a sub-coleção '${result.value.collection}' (${result.value.count} documentos).`);
        } else if (result.status === 'rejected') {
            logger.error(`[Exclusão] Falha ao limpar a sub-coleção '${result.reason.collection}':`, result.reason);
        }
    });

    logger.info(`[Exclusão] Limpeza de dados para o dependente ${dependentId} concluída.`);
});