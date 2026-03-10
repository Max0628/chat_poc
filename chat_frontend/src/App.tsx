import { useState, useRef, useEffect } from 'react'
import { Client } from '@stomp/stompjs'
import SockJS from 'sockjs-client'
import './App.css'

interface ChatMessage {
  fromUserId: string
  toUserId: string
  content: string
  timestamp: string
  groupId: string | null
}

type ConnectionStatus = 'disconnected' | 'connecting' | 'connected'

function App() {
  const [userId, setUserId] = useState('')
  const [toUserId, setToUserId] = useState('')
  const [serverPort, setServerPort] = useState<8080 | 8081>(8080)
  const [messageInput, setMessageInput] = useState('')
  const [messages, setMessages] = useState<ChatMessage[]>([])
  const [status, setStatus] = useState<ConnectionStatus>('disconnected')
  const clientRef = useRef<Client | null>(null)
  const messagesEndRef = useRef<HTMLDivElement | null>(null)

  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [messages])

  function connect() {
    if (!userId.trim()) return

    setStatus('connecting')

    const client = new Client({
      webSocketFactory: () => new SockJS(`http://localhost:${serverPort}/ws`),
      onConnect: () => {
        setStatus('connected')
        client.subscribe(`/user/${userId}/queue/messages`, (frame) => {
          const msg: ChatMessage = JSON.parse(frame.body)
          setMessages((prev) => [...prev, msg])
        })
      },
      onDisconnect: () => {
        setStatus('disconnected')
      },
      onStompError: () => {
        setStatus('disconnected')
      },
    })

    client.activate()
    clientRef.current = client
  }

  function disconnect() {
    clientRef.current?.deactivate()
    clientRef.current = null
    setStatus('disconnected')
  }

  function sendMessage() {
    if (!messageInput.trim() || !toUserId.trim() || status !== 'connected') return

    const msg: ChatMessage = {
      fromUserId: userId,
      toUserId: toUserId,
      content: messageInput,
      timestamp: new Date().toISOString(),
      groupId: null,
    }

    clientRef.current?.publish({
      destination: '/app/chat.send',
      body: JSON.stringify(msg),
    })

    setMessages((prev) => [...prev, msg])
    setMessageInput('')
  }

  function handleKeyDown(e: React.KeyboardEvent<HTMLInputElement>) {
    if (e.key === 'Enter') sendMessage()
  }

  const statusColor: Record<ConnectionStatus, string> = {
    disconnected: '#e74c3c',
    connecting: '#f39c12',
    connected: '#2ecc71',
  }

  return (
    <div style={{ maxWidth: 600, margin: '40px auto', fontFamily: 'sans-serif', padding: '0 16px' }}>
      <h2 style={{ marginBottom: 16 }}>Distributed Chat POC</h2>

      {/* Connection Panel */}
      <div style={{ border: '1px solid #ccc', borderRadius: 8, padding: 16, marginBottom: 16 }}>
        <div style={{ display: 'flex', gap: 8, marginBottom: 12, alignItems: 'center' }}>
          <input
            placeholder="Your user ID"
            value={userId}
            onChange={(e) => setUserId(e.target.value)}
            disabled={status !== 'disconnected'}
            style={{ flex: 1, padding: '6px 10px', borderRadius: 4, border: '1px solid #ccc' }}
          />
          <select
            value={serverPort}
            onChange={(e) => setServerPort(Number(e.target.value) as 8080 | 8081)}
            disabled={status !== 'disconnected'}
            style={{ padding: '6px 10px', borderRadius: 4, border: '1px solid #ccc' }}
          >
            <option value={8080}>Server :8080</option>
            <option value={8081}>Server :8081</option>
          </select>
        </div>
        <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
          <button
            onClick={status === 'disconnected' ? connect : disconnect}
            disabled={status === 'connecting' || (status === 'disconnected' && !userId.trim())}
            style={{
              padding: '6px 16px',
              borderRadius: 4,
              border: 'none',
              background: status === 'connected' ? '#e74c3c' : '#3498db',
              color: '#fff',
              cursor: 'pointer',
            }}
          >
            {status === 'connected' ? 'Disconnect' : status === 'connecting' ? 'Connecting…' : 'Connect'}
          </button>
          <span style={{ fontSize: 13, color: statusColor[status] }}>
            ● {status.charAt(0).toUpperCase() + status.slice(1)}
          </span>
        </div>
      </div>

      {/* Message List */}
      <div
        style={{
          border: '1px solid #ccc',
          borderRadius: 8,
          padding: 12,
          height: 320,
          overflowY: 'auto',
          marginBottom: 12,
          background: '#fafafa',
        }}
      >
        {messages.length === 0 ? (
          <p style={{ color: '#aaa', textAlign: 'center', marginTop: 120 }}>No messages yet</p>
        ) : (
          messages.map((msg, i) => {
            const isMine = msg.fromUserId === userId
            return (
              <div
                key={i}
                style={{
                  marginBottom: 10,
                  textAlign: isMine ? 'right' : 'left',
                }}
              >
                <span
                  style={{
                    display: 'inline-block',
                    background: isMine ? '#3498db' : '#ecf0f1',
                    color: isMine ? '#fff' : '#333',
                    padding: '6px 12px',
                    borderRadius: 16,
                    maxWidth: '75%',
                    wordBreak: 'break-word',
                  }}
                >
                  {!isMine && (
                    <strong style={{ display: 'block', fontSize: 11, marginBottom: 2 }}>
                      {msg.fromUserId}
                    </strong>
                  )}
                  {msg.content}
                </span>
                <div style={{ fontSize: 10, color: '#aaa', marginTop: 2 }}>
                  {new Date(msg.timestamp).toLocaleTimeString()}
                </div>
              </div>
            )
          })
        )}
        <div ref={messagesEndRef} />
      </div>

      {/* Send Panel */}
      <div style={{ display: 'flex', gap: 8 }}>
        <input
          placeholder="To user ID"
          value={toUserId}
          onChange={(e) => setToUserId(e.target.value)}
          disabled={status !== 'connected'}
          style={{ width: 120, padding: '6px 10px', borderRadius: 4, border: '1px solid #ccc' }}
        />
        <input
          placeholder="Type a message…"
          value={messageInput}
          onChange={(e) => setMessageInput(e.target.value)}
          onKeyDown={handleKeyDown}
          disabled={status !== 'connected'}
          style={{ flex: 1, padding: '6px 10px', borderRadius: 4, border: '1px solid #ccc' }}
        />
        <button
          onClick={sendMessage}
          disabled={status !== 'connected' || !messageInput.trim() || !toUserId.trim()}
          style={{
            padding: '6px 16px',
            borderRadius: 4,
            border: 'none',
            background: '#2ecc71',
            color: '#fff',
            cursor: 'pointer',
          }}
        >
          Send
        </button>
      </div>
    </div>
  )
}

export default App
