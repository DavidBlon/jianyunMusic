module.exports = {
    platform: 'fake-missing',
    version: '1.0.0',
    supportedSearchType: ['music'],
    async search(query) { return { data: [{ id: 'x' }], isEnd: true }; }  // 缺 name → 该条拒绝
};
