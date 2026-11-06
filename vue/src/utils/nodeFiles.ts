export interface FileChoice { value: number; text: string }

export function matchingNetworkFiles(byronId: number | null, files: FileChoice[]) {
  const name = files.find(file => file.value === byronId)?.text
  const match = name?.match(/^(.*)byron-genesis\.json$/i)
  if (!match) return {}
  const prefix = match[1]
  const names = {
    genesisShelley: 'shelley-genesis.json', genesisAlonzo: 'alonzo-genesis.json',
    genesisConway: 'conway-genesis.json', genesisDijkstra: 'dijkstra-genesis.json',
    config: 'config.json', topology: 'topology.json', peerSnapshot: 'peer-snapshot.json'
  }
  return Object.fromEntries(Object.entries(names).flatMap(([key, suffix]) => {
    const file = files.find(item => item.text.toLowerCase() === (prefix + suffix).toLowerCase())
    return file ? [[key, file.value]] : []
  })) as Partial<Record<keyof typeof names, number>>
}
