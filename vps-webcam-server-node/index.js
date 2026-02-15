//http://194.233.84.144:8080/video/e391f923-9afe-47e4-905e-bd9bafbe79db

const express = require('express');
const EventEmitter = require('events');
const app = express();
const port = 8080;

// Emitter untuk memberi sinyal saat frame baru tiba untuk stream tertentu
class StreamManager extends EventEmitter {}
const streamManager = new StreamManager();

// Penyimpanan in-memory sederhana untuk frame terbaru dari setiap stream
const streams = {};

// Middleware untuk mem-parsing body mentah untuk data gambar
app.use('/stream/:streamId', express.raw({
  type: 'image/jpeg',
  limit: '10mb' // Sesuaikan batas ukuran jika perlu
}));

// Endpoint untuk aplikasi Android mengirim (POST) frame gambar
app.post('/stream/:streamId', (req, res) => {
  const streamId = req.params.streamId;
  const frame = req.body;

  // Simpan frame terbaru
  streams[streamId] = frame;
  
  // Beri tahu listener bahwa frame baru tersedia untuk streamId ini
  streamManager.emit(`frame-${streamId}`, frame);
  
  // Kirim log ke konsol, mirip dengan log Anda sebelumnya
  console.log(`[${new Date().toISOString()}] Request dari ${req.ip} -> POST /stream/${streamId}`);
  
  res.status(200).send('OK');
});

// Endpoint untuk browser/klien melihat video stream
app.get('/video/:streamId', (req, res) => {
  const streamId = req.params.streamId;

  // Fungsi untuk memulai streaming ke klien
  function startStream() {
    // Atur header HTTP untuk MJPEG stream
    res.writeHead(200, {
      'Content-Type': 'multipart/x-mixed-replace; boundary=--frame',
      'Cache-Control': 'no-cache',
      'Connection': 'keep-alive',
      'Pragma': 'no-cache'
    });

    // Fungsi listener yang akan dipanggil setiap ada frame baru
    const frameListener = (frame) => {
      res.write(`--frame\n`);
      res.write(`Content-Type: image/jpeg\n`);
      res.write(`Content-Length: ${frame.length}\n\n`);
      res.write(frame);
      res.write(`\n`);
    };

    // Langsung kirim frame pertama jika sudah ada
    if (streams[streamId]) {
        frameListener(streams[streamId]);
    }

    // Pasang listener untuk frame-frame berikutnya
    streamManager.on(`frame-${streamId}`, frameListener);

    // Saat klien menutup koneksi, hapus listener untuk menghemat memori
    req.on('close', () => {
      streamManager.removeListener(`frame-${streamId}`, frameListener);
      res.end();
    });
  }
  
  // Jika stream belum ada, tunggu frame pertama datang
  if (!streams[streamId]) {
    streamManager.once(`frame-${streamId}`, startStream);
  } else {
    // Jika stream sudah ada, langsung mulai
    startStream();
  }
});

app.listen(port, () => {
  console.log(`Server streaming berjalan pada port ${port}`);
});