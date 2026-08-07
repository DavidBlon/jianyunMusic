module.exports = {
    platform: 'fake-huge',
    version: '1.0.0',
    supportedSearchType: ['music'],
    async search() {
        const items = [];
        for (let i = 0; i < 2000; i++) items.push({ id: 's' + i, name: 'n' + i });
        return { data: items, isEnd: true };
    }
};
