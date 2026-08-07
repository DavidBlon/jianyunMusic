module.exports = {
    platform: 'fake-hello',
    version: '1.0.0',
    supportedSearchType: ['music'],
    async search(query, page, type) {
        return {
            data: [{ id: 'hello-1', name: '测试 示例', artist: '测试歌手', duration: 200000 }],
            isEnd: true
        };
    },
    async getMediaSource(musicItem, quality) {
        return { url: 'https://media.example/' + musicItem.id + '.mp3', quality: quality || '128k' };
    },
    async getLyric(musicItem) {
        return { rawLrc: '[00:00.00]测试 示例', translation: null };
    }
};
