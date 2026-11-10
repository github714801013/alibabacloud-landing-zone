export function isServerSubscriptionChannel(channel: string): boolean {
  return channel.startsWith('conversation:') || channel.startsWith('scheduled-run:') || channel.startsWith('dispatch:');
}
