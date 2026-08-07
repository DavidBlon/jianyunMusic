module.exports = {
    platform: 'fake-throws',
    version: '1.0.0',
    supportedSearchType: ['music'],
    async search() { throw new Error('boom'); },
    async getMediaSource() { return Promise.reject('rejected'); }
};
