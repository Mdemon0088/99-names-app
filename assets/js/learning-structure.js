/* global window */
(() => {
  const DEFAULT_REVIEW_WEIGHT = 1;
  const DEFAULT_LESSON_WINDOW = 3;

  function normalizeNameRecord(record = {}) {
    return {
      id: Number(record.id) || 0,
      arabic: String(record.arabic || '').trim(),
      translit: String(record.translit || '').trim(),
      bn: String(record.bn || '').trim(),
      en: String(record.en || '').trim(),
      meaning: String(record.meaning || record.en || '').trim(),
      cue: String(record.cue || '').trim(),
      tags: Array.isArray(record.tags) ? record.tags.map(String) : []
    };
  }

  function normalizeDataset(dataset = []) {
    return Array.isArray(dataset)
      ? dataset.map(normalizeNameRecord).filter(item => item.arabic && item.translit && item.bn)
      : [];
  }

  function buildLessonPath(dataset = [], options = {}) {
    const names = normalizeDataset(dataset);
    const window = Math.max(1, Number(options.window || DEFAULT_LESSON_WINDOW));
    const seedIndex = Math.max(0, Number(options.seedIndex || 0));
    const slice = names.slice(seedIndex, seedIndex + window);
    const intro = slice[0] || names[0] || null;
    const matcher = slice.slice(0, 3);
    const builder = slice[1] || slice[0] || null;

    return {
      intro,
      matcher,
      builder,
      lessons: [
        { id: 'flashcard', type: 'flashcard', title: 'Lesson 1 • Flashcard', item: intro },
        { id: 'match', type: 'match', title: 'Lesson 2 • Match the pairs', items: matcher },
        { id: 'builder', type: 'builder', title: 'Lesson 3 • Word Builder', item: builder }
      ].filter(Boolean)
    };
  }

  function buildReviewQueue(progress = {}, dataset = []) {
    const names = normalizeDataset(dataset);
    const mistakes = Array.isArray(progress.mistakes) ? progress.mistakes : [];
    const seen = new Set();
    const queue = [];

    const pushUnique = (item, weight = DEFAULT_REVIEW_WEIGHT) => {
      if (!item || !item.arabic || seen.has(item.arabic)) return;
      seen.add(item.arabic);
      queue.push({ ...item, weight });
    };

    mistakes.forEach(entry => {
      const found = names.find(n => n.arabic === entry.arabic || String(n.id) === String(entry.id));
      if (found) pushUnique(found, Math.max(1, Number(entry.weight) || DEFAULT_REVIEW_WEIGHT));
    });

    if (!queue.length) {
      names.slice(0, 6).forEach(item => pushUnique(item));
    }

    return queue.sort((a, b) => b.weight - a.weight);
  }

  function pickNameOfTheDay(dataset = [], date = new Date()) {
    const names = normalizeDataset(dataset);
    if (!names.length) return null;
    const dayKey = `${date.getUTCFullYear()}-${date.getUTCMonth() + 1}-${date.getUTCDate()}`;
    let hash = 0;
    for (const ch of dayKey) hash = ((hash << 5) - hash) + ch.charCodeAt(0);
    const index = Math.abs(hash) % names.length;
    return names[index];
  }

  function buildStats(progress = {}, dataset = []) {
    const names = normalizeDataset(dataset);
    const correct = Number(progress.correct || 0);
    const mistakes = Number(progress.mistakes || 0);
    const total = Math.max(1, correct + mistakes);
    const completion = Math.min(100, Math.round((Number(progress.stepIndex || 0) / Math.max(1, progress.totalLessons || 3)) * 100));

    return {
      totalNames: names.length,
      completedLessons: Number(progress.completedLessons || 0),
      accuracy: Math.round((correct / total) * 100),
      completion,
      xp: Number(progress.xp || 0),
      streak: Number(progress.streak || 0),
      hearts: Number(progress.hearts || 0)
    };
  }

  window.AsmaulLearningStructure = {
    normalizeNameRecord,
    normalizeDataset,
    buildLessonPath,
    buildReviewQueue,
    pickNameOfTheDay,
    buildStats
  };
})();