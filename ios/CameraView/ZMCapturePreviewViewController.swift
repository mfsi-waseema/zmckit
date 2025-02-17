import UIKit
import AVKit

@available(iOS 13.0, *)
public class ZMCapturePreviewViewController: UIViewController {
    private let capturedImage: UIImage?
    private let videoURL: URL?
    
    private lazy var mediaView: UIView = {
        if let image = capturedImage {
            let imageView = UIImageView(image: image)
            imageView.contentMode = .scaleAspectFit
            return imageView
        } else if let videoURL = videoURL {
            let player = AVPlayer(url: videoURL)
            let playerLayer = AVPlayerLayer(player: player)
            playerLayer.videoGravity = .resizeAspect
            let playerView = UIView()
            playerView.layer.addSublayer(playerLayer)
            player.play()
            return playerView
        }
        return UIView()
    }()
    
    private lazy var buttonStack: UIStackView = {
        let stack = UIStackView()
        stack.axis = .horizontal
        stack.spacing = 20
        stack.distribution = .fillEqually
        return stack
    }()
    
    private lazy var shareButton: UIButton = {
        let button = UIButton()
        button.setImage(UIImage(systemName: "square.and.arrow.up"), for: .normal)
        button.tintColor = .white
        button.addTarget(self, action: #selector(shareButtonTapped), for: .touchUpInside)
        return button
    }()
    
    private lazy var downloadButton: UIButton = {
        let button = UIButton()
        button.setImage(UIImage(systemName: "arrow.down.to.line"), for: .normal)
        button.tintColor = .white
        button.addTarget(self, action: #selector(downloadButtonTapped), for: .touchUpInside)
        return button
    }()
    
    private lazy var closeButton: UIButton = {
        let button = UIButton()
        button.setImage(UIImage(systemName: "xmark"), for: .normal)
        button.tintColor = .white
        button.addTarget(self, action: #selector(closeButtonTapped), for: .touchUpInside)
        return button
    }()
    
    private lazy var messageLabel: UILabel = {
        let label = UILabel()
        label.textColor = .white
        label.textAlignment = .center
        label.alpha = 0
        return label
    }()
    
    public init(image: UIImage? = nil, videoURL: URL? = nil) {
        self.capturedImage = image
        self.videoURL = videoURL
        super.init(nibName: nil, bundle: nil)
    }
    
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }
    
    public override func viewDidLoad() {
        super.viewDidLoad()
        setupUI()
    }
    
    public override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        if let playerView = mediaView.layer.sublayers?.first as? AVPlayerLayer {
            playerView.frame = mediaView.bounds
        }
    }
    
    private func setupUI() {
        view.backgroundColor = .black
        
        view.addSubview(mediaView)
        view.addSubview(buttonStack)
        view.addSubview(closeButton)
        view.addSubview(messageLabel)
        
        buttonStack.addArrangedSubview(shareButton)
        buttonStack.addArrangedSubview(downloadButton)
        
        mediaView.translatesAutoresizingMaskIntoConstraints = false
        buttonStack.translatesAutoresizingMaskIntoConstraints = false
        closeButton.translatesAutoresizingMaskIntoConstraints = false
        messageLabel.translatesAutoresizingMaskIntoConstraints = false
        
        NSLayoutConstraint.activate([
            mediaView.topAnchor.constraint(equalTo: view.topAnchor),
            mediaView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            mediaView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            mediaView.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            
            buttonStack.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 20),
            buttonStack.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor, constant: -20),
            buttonStack.widthAnchor.constraint(equalToConstant: 120),
            buttonStack.heightAnchor.constraint(equalToConstant: 44),
            
            closeButton.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 20),
            closeButton.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -20),
            closeButton.widthAnchor.constraint(equalToConstant: 44),
            closeButton.heightAnchor.constraint(equalToConstant: 44),
            
            messageLabel.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            messageLabel.bottomAnchor.constraint(equalTo: buttonStack.topAnchor, constant: -20),
        ])
    }
    
    private func showMessage(_ text: String) {
        messageLabel.text = text
        UIView.animate(withDuration: 0.3, animations: {
            self.messageLabel.alpha = 1
        }) { _ in
            DispatchQueue.main.asyncAfter(deadline: .now() + 2) {
                UIView.animate(withDuration: 0.3) {
                    self.messageLabel.alpha = 0
                }
            }
        }
    }
    
    @objc private func shareButtonTapped() {
        var items: [Any] = []
        if let image = capturedImage {
            items.append(image)
        } else if let videoURL = videoURL {
            items.append(videoURL)
        }
        
        let activityVC = UIActivityViewController(activityItems: items, applicationActivities: nil)
        present(activityVC, animated: true)
    }
    
    @objc private func downloadButtonTapped() {
        if let image = capturedImage {
            UIImageWriteToSavedPhotosAlbum(image, self, #selector(media(_:didFinishSavingWithError:contextInfo:)), nil)
            showMessage("Fotoğraf kaydedildi!")
        } else if let videoURL = videoURL {
            if UIVideoAtPathIsCompatibleWithSavedPhotosAlbum(videoURL.path) {
                UISaveVideoAtPathToSavedPhotosAlbum(videoURL.path, self, #selector(media(_:didFinishSavingWithError:contextInfo:)), nil)
                showMessage("Video kaydedildi!")
            }
        }
    }
    
    @objc private func media(_ media: Any, didFinishSavingWithError error: Error?, contextInfo: UnsafeRawPointer) {
        if let error = error {
            print("Error saving media: \(error.localizedDescription)")
        } else {
            print("Media saved successfully")
        }
    }
    
    @objc private func closeButtonTapped() {
        dismiss(animated: true)
    }
} 