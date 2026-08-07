module.exports = {
    platform: 'fake-timeout',
    version: '1.0.0',
    supportedSearchType: ['music'],
    async search() { await new Promise((resolve) => setTimeout(resolve, 60000)); return { data: [], isEnd: true }; }
};
